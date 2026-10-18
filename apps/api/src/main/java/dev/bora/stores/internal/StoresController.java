package dev.bora.stores.internal;

import dev.bora.identity.CurrentUser;
import dev.bora.identity.DemoRole;
import dev.bora.shared.ApiException;
import dev.bora.stores.StoreDirectory;
import dev.bora.stores.StoreView;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stores")
class StoresController {
  private final CurrentUser currentUser;
  private final StoreDirectory stores;

  StoresController(CurrentUser currentUser, StoreDirectory stores) {
    this.currentUser = currentUser;
    this.stores = stores;
  }

  @GetMapping("/me")
  @Operation(operationId = "getCurrentStore", summary = "Consultar a loja autenticada")
  StoreView me() {
    var user = currentUser.require();
    if (user.role() != DemoRole.STORE || user.storeId() == null) {
      throw new ApiException(
          HttpStatus.FORBIDDEN, "STORE_ROLE_REQUIRED", "Entre como loja para consultar sua loja.");
    }
    return stores.find(user.storeId());
  }
}
