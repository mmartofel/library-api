package pl.kartofel.library.config;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.kartofel.library.controller.ApiExceptionHandler.Problem;

import java.util.Set;

@Configuration
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
public class OpenApiConfig {

    /** Resources other records point to; deleting them returns 409 {@code in-use} while referenced. */
    private static final Set<String> REFERENCED_PATHS = Set.of("/api/authors/{id}");

    /** The contract version follows the Maven project version. */
    @Bean
    OpenAPI openApi(BuildProperties build) {
        return new OpenAPI().info(new Info().title("Library API").version(build.getVersion()));
    }

    /**
     * Documents Problem Details error responses by convention: 400 and 409 on POST/PUT, 400 on list GETs
     * (paths without {id}), 404 on paths with {id}, 409 on DELETE of a {@link #REFERENCED_PATHS referenced} resource.
     * Declaring them with {@code @ApiResponse} instead would stop springdoc from generating the success response.
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return openApi -> {
            openApi.getComponents().getSchemas().putAll(ModelConverters.getInstance(true).readAll(Problem.class));
            openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                boolean write = method == HttpMethod.POST || method == HttpMethod.PUT;
                if (write) {
                    addProblem(operation, "400",
                            "Invalid request; field errors in `errors` (code `not-found` for an unknown reference)");
                    addProblem(operation, "409",
                            "Value already in use (`errors` with code `unique`) or stale `version` (no `errors`)");
                }
                if (method == HttpMethod.GET && !path.contains("{id}")) {
                    addProblem(operation, "400", "Invalid filter (field errors in `errors`) or sort");
                }
                if (path.contains("{id}")) {
                    addProblem(operation, "404", "Not found");
                }
                if (method == HttpMethod.DELETE && REFERENCED_PATHS.contains(path)) {
                    addProblem(operation, "409", "Still referenced by other records (`errors` with code `in-use`, no `field`)");
                }
            }));
        };
    }

    private static void addProblem(Operation operation, String status, String description) {
        Schema<?> problem = new Schema<>().$ref("#/components/schemas/Problem");
        operation.getResponses().addApiResponse(status, new ApiResponse().description(description)
                .content(new Content().addMediaType("application/problem+json", new MediaType().schema(problem))));
    }
}
