package org.rakam.analysis;

import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.QualifiedName;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import io.airlift.units.Duration;
import org.rakam.plugin.ContinuousQuery;
import org.rakam.report.QueryExecutor;
import org.rakam.report.realtime.AggregationType;
import org.rakam.report.realtime.RealTimeConfig;
import org.rakam.report.realtime.RealTimeReport;
import org.rakam.util.JsonResponse;
import org.rakam.util.NotImplementedException;
import org.rakam.util.RakamException;
import org.rakam.util.ValidationUtil;

import javax.inject.Qualifier;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.facebook.presto.sql.RakamSqlFormatter.formatExpression;
import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static java.lang.Boolean.TRUE;
import static java.lang.String.format;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;
import static org.rakam.util.ValidationUtil.checkTableColumn;

public class RealtimeService
{
    private final String timestampToEpochFunction;
    private final SqlParser sqlParser = new SqlParser();
    private final Duration slide;
    private final ContinuousQueryService service;
    private final QueryExecutor executor;
    private final Duration window;
    private final List<AggregationType> aggregationTypes;

    @Inject
    public RealtimeService(ContinuousQueryService service, QueryExecutor executor, @RealtimeAggregations List<AggregationType> aggregationTypes, RealTimeConfig config, @TimestampToEpochFunction String timestampToEpochFunction)
    {
        this.service = service;
        this.timestampToEpochFunction = timestampToEpochFunction;
        RealTimeConfig realTimeConfig = requireNonNull(config, "config is null");
        this.window = realTimeConfig.getWindowInterval();
        this.slide = realTimeConfig.getSlideInterval();
        this.aggregationTypes = aggregationTypes;
        this.executor = executor;
    }

    public CompletableFuture<JsonResponse> create(String project, RealTimeReport report)
    {
        String unsupportedMeasures = report.measures.stream()
                .filter(e -> !aggregationTypes.contains(e.aggregation))
                .map(e -> e.aggregation.name())
                .collect(Collectors.joining(", "));
        if (!unsupportedMeasures.isEmpty()) {
            throw new RakamException("Unsupported aggregation types: " + unsupportedMeasures, BAD_REQUEST);
        }

        String sqlQuery = new StringBuilder().append("select ")
                .append(format("(cast(" + timestampToEpochFunction + "(_time) as bigint) / %d) as _time, ", slide.roundTo(TimeUnit.SECONDS)))
                .append(createFinalSelect(report.measures, report.dimensions))
                .append(" FROM (" + report.collections.stream().map(col -> String.format("(SELECT %s FROM %s) as data",
                        Stream.of("_time", report.dimensions.stream().collect(Collectors.joining(", ")),
                                report.measures.stream().map(e -> e.column).distinct().collect(Collectors.joining(", "))).filter(e -> !e.isEmpty()).collect(Collectors.joining(", ")), col
                )).collect(Collectors.joining(" UNION ALL ")) + ")")
                .append(report.filter == null ? "" : " where " + report.filter)
                .append(" group by 1 ")
                .append(report.dimensions != null ?
                        IntStream.range(0, report.dimensions.size()).mapToObj(i -> ", " + (i + 2)).collect(Collectors.joining("")) : "")
                .toString();

        ContinuousQuery query = new ContinuousQuery(report.table_name, report.name,
                sqlQuery,
                ImmutableList.of(),
                ImmutableMap.of("realtime", true, "aggregation", report.measures));
        return service.create(project, query, false).getResult().thenApply(JsonResponse::map);
    }

    public CompletableFuture<Boolean> delete(String project, String tableName)
    {
        return service.delete(project, tableName);
    }

    public CompletableFuture<RealTimeQueryResult> query(String project,
            String tableName,
            String filter,
            RealTimeReport.Measure measure,
            List<String> dimensions,
            Boolean aggregate,
            Instant dateStart,
            Instant dateEnd)
    {
        Expression expression;
        if (filter != null) {
            expression = sqlParser.createExpression(filter);
        }
        else {
            expression = null;
        }

        if (aggregate == null) {
            aggregate = false;
        }

        boolean noDimension = dimensions == null || dimensions.isEmpty();

        long last_update = Instant.now().toEpochMilli() - slide.toMillis();
        long previousWindow = (dateStart == null ? (last_update - window.toMillis()) : dateStart.toEpochMilli()) / (slide.toMillis());
        long currentWindow = (dateEnd == null ? last_update : dateEnd.toEpochMilli()) / slide.toMillis();

        Object timeCol = aggregate ? currentWindow : "_time";
        String sqlQuery = format("select %s, %s %s from %s where %s %s %s ORDER BY 1 ASC LIMIT 5000",
                timeCol + " * cast(" + slide.toMillis() + " as bigint)",
                !noDimension ? dimensions.stream().map(ValidationUtil::checkTableColumn).collect(Collectors.joining(", ")) + "," : "",
                String.format(combineFunction(measure.aggregation), checkTableColumn(measure.column + "_" + measure.aggregation.name().toLowerCase(), "measure column is not valid")),
                executor.formatTableReference(project, QualifiedName.of("continuous", tableName)),
                format("_time >= %d", previousWindow) +
                        (dateEnd == null ? "" :
                                format("AND _time <", format("_time >= %d AND _time <= %d", previousWindow, currentWindow))),
                !noDimension || !aggregate ? format("GROUP BY %s %s %s", !aggregate ? timeCol : "", !aggregate && !noDimension ? "," : "", dimensions.stream().map(ValidationUtil::checkTableColumn).collect(Collectors.joining(", "))) : "",
                (expression == null) ? "" : formatExpression(expression, reference -> executor.formatTableReference(project, reference)));

        final boolean finalAggregate = aggregate;
        return executor.executeRawQuery(sqlQuery).getResult().thenApply(result -> {
            if (result.isFailed()) {
                // TODO: be sure that this exception is catched
                throw new RakamException(result.getError().message, INTERNAL_SERVER_ERROR);
            }

            long previousTimestamp = previousWindow * slide.toMillis() / 1000;
            long currentTimestamp = currentWindow * slide.toMillis() / 1000;

            List<List<Object>> data = result.getResult();

            if (!finalAggregate) {
                if (noDimension) {
                    List<List<Object>> newData = Lists.newLinkedList();
                    Map<Long, List<Object>> collect = data.stream().collect(Collectors.toMap(new Function<List<Object>, Long>()
                    {
                        @Override
                        public Long apply(List<Object> objects)
                        {
                            return (Long) objects.get(0);
                        }
                    }, Function.identity()));
                    for (long current = previousWindow * slide.toMillis(); current < currentWindow * slide.toMillis(); current += slide.toMillis()) {

                        List<Object> objects = collect.get(current);

                        if (objects != null) {
                            ArrayList<Object> list = new ArrayList<>(2);
                            list.add(current);

                            list.add(objects.get(dimensions.size() + 1));
                            newData.add(list);
                            continue;
                        }

                        ArrayList<Object> list = new ArrayList<>(2);
                        list.add(current);

                        list.add(0);
                        newData.add(list);
                    }
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, newData);
                }
                else {
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, data);
                }
            }
            else {
                if (noDimension) {
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, !data.isEmpty() ? data.get(0).get(1) : 0);
                }
                else {
                    return new RealTimeQueryResult(previousTimestamp, currentTimestamp, data);
                }
            }
        });
    }

    private String createFinalSelect(List<RealTimeReport.Measure> measures, List<String> dimensions)
    {
        StringBuilder builder = new StringBuilder();
        if (dimensions != null && !dimensions.isEmpty()) {
            builder.append(" " + dimensions.stream().collect(Collectors.joining(", ")) + ", ");
        }

        for (int i = 0; i < measures.size(); i++) {
            if (measures.get(i).aggregation == AggregationType.AVERAGE) {
                throw new RakamException("Average aggregation is not supported in realtime service.", BAD_REQUEST);
            }

            String format;
            switch (measures.get(i).aggregation) {
                case MAXIMUM:
                    format = "max(%s)";
                    break;
                case MINIMUM:
                    format = "min(%s)";
                    break;
                case COUNT:
                    format = "count(%s)";
                    break;
                case SUM:
                    format = "sum(%s)";
                    break;
                case APPROXIMATE_UNIQUE:
                    format = "approx_set(%s)";
                    break;
                default:
                    throw new IllegalArgumentException("aggregation type couldn't found.");
            }

            builder.append(String.format(format + " as %s_%s ", measures.get(i).column,
                    measures.get(i).column, measures.get(i).aggregation.name().toLowerCase()));
            if (i < measures.size() - 1) {
                builder.append(", ");
            }
        }

        return builder.toString();
    }

    private String combineFunction(AggregationType aggregationType)
    {
        switch (aggregationType) {
            case COUNT:
            case SUM:
                return "sum(%s)";
            case MINIMUM:
                return "min(%s)";
            case MAXIMUM:
                return "max(%s)";
            case APPROXIMATE_UNIQUE:
                return "cardinality(merge(%s))";
            default:
                throw new NotImplementedException();
        }
    }

    public List<ContinuousQuery> list(String project)
    {
        return service.list(project).stream()
                .filter(c -> TRUE.equals(c.options.get("realtime")))
                .collect(Collectors.toList());
    }

    public static class RealTimeQueryResult
    {
        public final long start;
        public final long end;
        public final Object result;

        public RealTimeQueryResult(long start, long end, Object result)
        {
            this.start = start;
            this.end = end;
            this.result = result;
        }
    }

    @Qualifier
    @Documented
    @Retention(RUNTIME)
    public @interface RealtimeAggregations
    {

    }
}
