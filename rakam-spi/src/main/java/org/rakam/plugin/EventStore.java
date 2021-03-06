package org.rakam.plugin;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.rakam.collection.Event;
import org.rakam.report.QueryExecution;
import org.rakam.report.realtime.AggregationType;

import java.net.URL;
import java.util.List;
import java.util.Map;

public interface EventStore {
    int[] SUCCESSFUL_BATCH = new int[0];

    void store(Event event);

    int[] storeBatch(List<Event> events);

    default void storeBulk(List<Event> events) {
        storeBatch(events);
    }

    default QueryExecution commit(String project, String collection) {
        throw new UnsupportedOperationException();
    }

    default QueryExecution copy(String project, String collection, List<URL> url, CopyType type, CompressionType compressionType, Map<String, String> options) {
        throw new UnsupportedOperationException();
    }

    enum CopyType {
        AVRO, CSV, JSON;

        @JsonCreator
        public static CopyType get(String name) {
            return valueOf(name.toUpperCase());
        }

        @JsonProperty
        public String value() {
            return name();
        }
    }

    enum CompressionType
    {
        GZIP;

        @JsonCreator
        public static CompressionType get(String name) {
            return valueOf(name.toUpperCase());
        }

        @JsonProperty
        public String value() {
            return name();
        }
    }
}
