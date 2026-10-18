package dev.bora.web.internal;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

  @Bean
  OpenAPI boraOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Bora API")
                .version("v1")
                .description("Demonstração de despacho de entregas em São Paulo."))
        .servers(List.of(new Server().url("/")));
  }

  @Bean
  OpenApiCustomizer securityAndProblemDetails() {
    return api -> {
      Components components = api.getComponents();
      components
          .addSecuritySchemes(
              "sessionCookie",
              new SecurityScheme()
                  .type(SecurityScheme.Type.APIKEY)
                  .in(SecurityScheme.In.COOKIE)
                  .name("BORA_SESSION")
                  .description(
                      "Cookie HttpOnly da sessão, enviado pelo navegador. Obter CSRF cria uma sessão anônima; entrar na demonstração autentica essa sessão."))
          .addSecuritySchemes(
              "csrfToken",
              new SecurityScheme()
                  .type(SecurityScheme.Type.APIKEY)
                  .in(SecurityScheme.In.HEADER)
                  .name("X-CSRF-TOKEN")
                  .description(
                      "Token devolvido por GET /api/v1/auth/csrf, vinculado ao cookie da mesma sessão. Obtenha outro após entrar, trocar de papel ou sair."))
          .addSchemas("ApiProblem", problemSchema());
      nullable(components, "SessionView", "user");
      nullable(components, "DemoUserView", "storeId", "courierId");
      nullable(components, "OrderSummary", "courierId");
      nullable(components, "OrderView", "courierId", "confirmationCode");
      nullable(components, "OrderEventView", "fromStatus");

      api.getPaths()
          .forEach(
              (path, item) ->
                  item.readOperationsMap()
                      .forEach(
                          (method, operation) -> {
                            boolean unsafe = method != PathItem.HttpMethod.GET;
                            boolean authenticated = !path.startsWith("/api/v1/auth/");
                            if (authenticated || unsafe) {
                              SecurityRequirement security =
                                  new SecurityRequirement().addList("sessionCookie");
                              if (unsafe) security.addList("csrfToken");
                              operation.setSecurity(List.of(security));
                            } else {
                              operation.setSecurity(List.of());
                            }
                            if (authenticated) {
                              error(
                                  operation.getResponses(),
                                  "401",
                                  "Entre na demonstração para continuar.");
                              error(
                                  operation.getResponses(),
                                  "403",
                                  "O papel ou a sessão não permite esta ação.");
                            }
                            if (unsafe) {
                              error(
                                  operation.getResponses(),
                                  "403",
                                  "O token CSRF ou o papel não permite esta ação.");
                            }
                            switch (operation.getOperationId()) {
                              case "enterDemo" -> {
                                error(
                                    operation.getResponses(), "400", "Confira o papel informado.");
                                error(
                                    operation.getResponses(),
                                    "429",
                                    "Limite de entradas atingido.");
                              }
                              case "createOrder" -> {
                                error(
                                    operation.getResponses(),
                                    "400",
                                    "Confira o destino e a chave de idempotência.");
                                error(
                                    operation.getResponses(),
                                    "409",
                                    "A chave de idempotência já foi usada para outro destino.");
                                error(
                                    operation.getResponses(),
                                    "422",
                                    "O destino fica fora da área atendida ou não possui rota de moto.");
                                error(
                                    operation.getResponses(),
                                    "429",
                                    "Limite de criação ou de pedidos ativos atingido.");
                                error(
                                    operation.getResponses(),
                                    "503",
                                    "O grafo de roteamento ainda não está disponível.");
                              }
                              case "listOrders" ->
                                  error(
                                      operation.getResponses(),
                                      "400",
                                      "Confira os parâmetros de paginação.");
                              case "getOrder", "getCurrentStore" ->
                                  error(
                                      operation.getResponses(),
                                      "404",
                                      "O recurso não existe ou não está visível neste contexto.");
                              case "cancelOrder" -> {
                                error(
                                    operation.getResponses(),
                                    "400",
                                    "Confira o pedido e sua versão.");
                                error(
                                    operation.getResponses(),
                                    "404",
                                    "O pedido não existe ou não está visível neste contexto.");
                                error(
                                    operation.getResponses(),
                                    "409",
                                    "A versão ou a etapa do pedido mudou. Consulte o pedido novamente.");
                              }
                              default -> {}
                            }
                          }));
    };
  }

  private static void nullable(Components components, String name, String... properties) {
    Schema<?> model = components.getSchemas().get(name);
    if (model == null || model.getProperties() == null) return;
    for (String property : properties) {
      Schema<?> original = model.getProperties().get(property);
      if (original != null) {
        if (original.get$ref() != null) {
          original = new Schema<>().$ref(original.get$ref());
        }
        original.setNullable(null);
        model.addProperty(
            property,
            new ComposedSchema()
                .addAnyOfItem(original)
                .addAnyOfItem(new Schema<>(SpecVersion.V31).types(Set.of("null"))));
      }
    }
  }

  private static Schema<?> problemSchema() {
    return new ObjectSchema()
        .addProperty("type", new StringSchema().format("uri"))
        .addProperty("title", new StringSchema())
        .addProperty("status", new IntegerSchema())
        .addProperty("detail", new StringSchema())
        .addProperty("instance", new StringSchema().format("uri"))
        .addProperty("code", new StringSchema())
        .addProperty(
            "errors",
            new ArraySchema()
                .items(
                    new ObjectSchema()
                        .addProperty("field", new StringSchema())
                        .addProperty("message", new StringSchema())
                        .required(List.of("field", "message"))))
        .required(List.of("type", "title", "status", "detail", "code"));
  }

  private static void error(
      io.swagger.v3.oas.models.responses.ApiResponses responses,
      String status,
      String description) {
    ApiResponse response =
        new ApiResponse()
            .description(description)
            .content(
                new Content()
                    .addMediaType(
                        "application/problem+json",
                        new MediaType()
                            .schema(new Schema<>().$ref("#/components/schemas/ApiProblem"))));
    if ("429".equals(status)) {
      response.addHeaderObject(
          "Retry-After",
          new Header()
              .description(
                  "Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido.")
              .required(false)
              .schema(new IntegerSchema().minimum(BigDecimal.ZERO)));
    }
    responses.addApiResponse(status, response);
  }
}
