package org.rakam.recipe;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JSR310Module;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.rakam.analysis.ApiKeyService;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.SwaggerJacksonAnnotationIntrospector;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.HeaderParam;
import org.rakam.util.JsonHelper;
import org.rakam.util.JsonResponse;
import org.rakam.util.RakamException;

import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaders.Names.ACCESS_CONTROL_ALLOW_ORIGIN;
import static io.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaders.Names.ORIGIN;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.rakam.analysis.ApiKeyService.AccessKeyType.MASTER_KEY;
import static org.rakam.recipe.RecipeHttpService.ExportType.YAML;
import static org.rakam.server.http.HttpServer.returnError;

@Path("/recipe")
@Api(value = "/recipe", nickname = "recipe", description = "Recipe operations", tags = "recipe")
public class RecipeHttpService extends HttpService {
    private static ObjectMapper yamlMapper;
    private final ApiKeyService apiKeyService;

    static {
        yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.registerModule(new JSR310Module());
        yamlMapper.registerModule(new Jdk8Module());

        SwaggerJacksonAnnotationIntrospector ai = new SwaggerJacksonAnnotationIntrospector();
        yamlMapper.registerModule(
                new SimpleModule("swagger", Version.unknownVersion()) {
                    @Override
                    public void setupModule(SetupContext context) {
                        context.insertAnnotationIntrospector(ai);
                    }
                });
    }

    private final RecipeHandler installer;

    @Inject
    public RecipeHttpService(RecipeHandler installer, ApiKeyService apiKeyService) {
        this.installer = installer;
        this.apiKeyService = apiKeyService;
    }

    @ApiOperation(value = "Install recipe",
            authorizations = @Authorization(value = "master_key")
    )
    @POST
    @Path("/install")
    public void install(RakamHttpRequest request) {
        String contentType = request.headers().get(CONTENT_TYPE);
        ExportType exportType = Arrays.stream(ExportType.values())
                .filter(f -> f.contentType.equals(contentType))
                .findAny()
                .orElse(YAML);

        boolean override = ImmutableList.of(Boolean.TRUE.toString())
                .equals(request.params().get("override"));

        request.bodyHandler(body -> {
            Recipe recipe;

            try {
                recipe = exportType.mapper.readValue(body, Recipe.class);
            } catch (IOException e) {
                returnError(request, e.getMessage(), HttpResponseStatus.BAD_REQUEST);
                return;
            }

            String master_key = request.headers().get("master_key");
            String project = apiKeyService.getProjectOfApiKey(master_key, MASTER_KEY);

            try {
                installer.install(recipe, project, override);
                request.response(JsonHelper.encode(JsonResponse.success())).end();
            } catch (Exception e) {
                returnError(request, "Error loading recipe: " + e.getMessage(), HttpResponseStatus.BAD_REQUEST);
            }
        });
    }

    @ApiOperation(value = "Export recipe", request = ExportRequest.class, response = Recipe.class,
            authorizations = @Authorization(value = "master_key")
    )
    @GET
    @Path("/export")
    public void export(@HeaderParam("Accept") String contentType, @Named("project") String project, RakamHttpRequest request) throws JsonProcessingException {
        request.bodyHandler(s -> {
            Recipe export = installer.export(project);

            ExportType exportType = Arrays.stream(ExportType.values())
                    .filter(f -> f.contentType.equals(contentType))
                    .findAny()
                    .orElse(YAML);

            ByteBuf buffer;
            try {
                buffer = Unpooled.wrappedBuffer(exportType.mapper.writeValueAsBytes(export));
            } catch (JsonProcessingException e) {
                throw Throwables.propagate(e);
            }

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, buffer);
            response.headers().add(CONTENT_TYPE, exportType.contentType);
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, request.headers().get(ORIGIN));

            request.response(response).end();
        });
    }

    public static class ExportRequest {
        public final ExportType type;

        @JsonCreator
        public ExportRequest(@ApiParam(value = "type", required = false) ExportType type) {
            this.type = type;
        }
    }

    public enum ExportType {
        JSON(JsonHelper.getMapper(), "application/json"), YAML(yamlMapper, "application/x-yaml");

        private final ObjectMapper mapper;
        private final String contentType;

        ExportType(ObjectMapper mapper, String contentType) {
            this.mapper = mapper;
            this.contentType = contentType;
        }

        @JsonCreator
        public static ExportType get(String name) {
            return valueOf(name.toUpperCase());
        }
    }
}
