/* Tipos gerados do contrato da API. Execute npm run contract:generate na pasta apps/web. */
export interface paths {
  '/api/v1/auth/csrf': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Obter a proteção CSRF da sessão */
    get: operations['getCsrf'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/demo': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Entrar na demonstração ou trocar de papel */
    post: operations['enterDemo'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/logout': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Sair da demonstração e encerrar o contexto visitante */
    post: operations['logoutDemo'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/me': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar a sessão atual */
    get: operations['getSession'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/availability': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Ficar online ou offline */
    post: operations['setCourierAvailability'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/me': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar o entregador e sua posição gravada */
    get: operations['getCurrentCourier'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/offer': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar a oferta atual do entregador */
    get: operations['getCourierOffer'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/positions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Receber um lote de posições
     * @description O recibo confirma o recebimento em memória. Consulte a posição gravada após o prazo de gravação.
     */
    post: operations['submitCourierPositions'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/route': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar a rota até a próxima etapa */
    get: operations['getCourierRoute'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/training-order': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Criar um pedido privado de treino com oferta para o próprio entregador
     * @description Repetir a mesma Idempotency-Key preserva o pedido, a oferta e o prazo original. O código de confirmação é devolvido somente ao criador nesta resposta.
     */
    post: operations['createCourierTrainingOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/training-order/{id}/cancel': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Encerrar um treino do próprio entregador antes da coleta */
    post: operations['cancelCourierTrainingOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/courier/work': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar o trabalho do entregador */
    get: operations['getCourierWork'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/map/config': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar o fator de velocidade da simulação */
    get: operations['getMapConfig'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/map/snapshot': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar posições simuladas na área visível */
    get: operations['getPublicMapSnapshot'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/offers/{id}/accept': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Aceitar uma oferta dentro do prazo */
    post: operations['acceptOffer'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/offers/{id}/reject': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Recusar uma oferta dentro do prazo */
    post: operations['rejectOffer'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/ops/metrics': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Consultar os indicadores da operação
     * @description Conta pedidos públicos simulados e do próprio contexto numa consulta coerente. Espera considera a tentativa atual de busca; os alertas excluem pedidos encerrados. Os totais independem do viewport e da página ou filtros abertos na interface.
     */
    get: operations['getOperationsMetrics'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/ops/orders/{id}/reassign': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Redistribuir um pedido da operação */
    post: operations['reassignOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/ops/snapshot': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar posições públicas e do próprio contexto */
    get: operations['getOperationsSnapshot'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/orders': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Listar pedidos visíveis
     * @description Filtra todo o conjunto autorizado por estado, código público exato e escopo. Reutilize snapshotAt nas próximas páginas para excluir novas criações; os estados continuam atuais. OWN mostra o próprio contexto, PUBLIC o cenário simulado e ALL combina ambos. WAITING é exclusivo da operação com status SEARCHING e ordena pelo início da tentativa atual de busca; size permanece entre 1 e 100.
     */
    get: operations['listOrders'];
    put?: never;
    /**
     * Criar um pedido de entrega
     * @description Cria um pedido da loja autenticada. A mesma chave e o mesmo destino repetem a resposta original.
     */
    post: operations['createOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/orders/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar um pedido e sua linha do tempo */
    get: operations['getOrder'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/orders/{id}/cancel': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Cancelar um pedido
     * @description Exige a versão atual e respeita o papel, a propriedade e a etapa do pedido.
     */
    post: operations['cancelOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/orders/{id}/deliver': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Confirmar a entrega ao cliente */
    post: operations['deliverOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/orders/{id}/pickup': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /** Confirmar a coleta na loja */
    post: operations['pickUpOrder'];
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/stores/me': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar a loja autenticada */
    get: operations['getCurrentStore'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/tracking/{code}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Consultar o progresso limitado de um link de rastreio */
    get: operations['getTracking'];
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    ApiProblem: {
      code: string;
      detail: string;
      errors?: {
        field: string;
        message: string;
      }[];
      /** Formato: uri */
      instance?: string;
      /** Formato: int32 */
      status: number;
      title: string;
      /** Formato: uri */
      type: string;
    };
    AssignedOrderView: {
      /** Formato: date-time */
      assignedAt: string;
      atStoreAt?: string | null;
      confirmationRequired: boolean;
      dropoff: components['schemas']['GeoPoint'];
      /** Formato: uuid */
      id: string;
      pickedUpAt?: string | null;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      store: components['schemas']['StoreView'];
      /** Formato: int64 */
      version: number;
    };
    AvailabilityRequest: {
      online: boolean;
      position?: components['schemas']['GeoPoint'];
    };
    CancelOrderRequest: {
      /** Formato: int64 */
      expectedVersion: number;
    };
    CancelTrainingOrderRequest: {
      /** Formato: int64 */
      expectedVersion: number;
    };
    CourierOffer: {
      /** Formato: date-time */
      createdAt: string;
      dropoff: components['schemas']['GeoPoint'];
      /**
       * Format: int32
       * @description Tempo até a loja em segundos, já ajustado à velocidade da simulação.
       */
      etaToPickupSeconds: number;
      /** Formato: date-time */
      expiresAt: string;
      /** Formato: uuid */
      id: string;
      /** Formato: uuid */
      orderId: string;
      store: components['schemas']['StoreView'];
    };
    CourierOfferInbox: {
      offer: components['schemas']['CourierOffer'] | null;
      /** Formato: date-time */
      serverTime: string;
    };
    CourierRouteView: {
      /** @enum {string} */
      destination: 'DROPOFF' | 'PICKUP';
      /** Formato: double */
      distanceMeters: number;
      /** Formato: int64 */
      durationMillis: number;
      geometry: components['schemas']['GeoPoint'][];
      /** Formato: uuid */
      orderId: string;
      /** Formato: int64 */
      simulatedDurationMillis: number;
      target: components['schemas']['GeoPoint'];
    };
    CourierView: {
      /** Formato: uuid */
      id: string;
      location: components['schemas']['GeoPoint'] | null;
      locationUpdatedAt: (null | string) | null;
      name: string;
      /** @enum {string} */
      status: 'AVAILABLE' | 'BUSY' | 'OFFERED' | 'OFFLINE';
      /** Formato: int64 */
      version: number;
    };
    CourierWorkView: {
      courier: components['schemas']['CourierView'];
      order?: components['schemas']['AssignedOrderView'] | null;
    };
    CreateOrderRequest: {
      dropoff: components['schemas']['DropoffRequest'];
    };
    CsrfView: {
      headerName: string;
      token: string;
    };
    DeliverRequest: {
      confirmationCode?: string;
      /** Formato: int64 */
      expectedVersion: number;
    };
    DemoLogin: {
      /** @enum {string} */
      role: 'COURIER' | 'OPERATIONS' | 'STORE';
    };
    DemoUserView: {
      courierId?: string | null;
      displayName: string;
      /** Formato: date-time */
      expiresAt: string;
      /** Formato: uuid */
      id: string;
      /** @enum {string} */
      role: 'COURIER' | 'OPERATIONS' | 'STORE';
      storeId?: string | null;
    };
    DropoffRequest: {
      /** Formato: double */
      latitude: number;
      /** Formato: double */
      longitude: number;
    };
    GeoPoint: {
      /** Formato: double */
      latitude: number;
      /** Formato: double */
      longitude: number;
    };
    MapFrame: {
      points: unknown[][];
      /** Formato: date-time */
      serverTime: string;
    };
    OperationsMetrics: {
      /** Formato: int64 */
      activeOrders: number;
      /** Formato: int64 */
      deliveredLastHour: number;
      /** Formato: int64 */
      dueWithinFiveMinutes: number;
      /** Formato: int64 */
      overdueOrders: number;
      /** Formato: int64 */
      searchingOrders: number;
      /** Formato: int64 */
      searchingOverThreeMinutes: number;
      /** Formato: date-time */
      serverTime: string;
    };
    OrderEventView: {
      fromStatus?:
        ('ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING') | null;
      /** Formato: int64 */
      id: number;
      /** Formato: date-time */
      occurredAt: string;
      /** Formato: int64 */
      orderVersion: number;
      /** @enum {string} */
      toStatus: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      type: string;
    };
    OrderPage: {
      items: components['schemas']['OrderSummary'][];
      /** Formato: int32 */
      page: number;
      /** Formato: int32 */
      size: number;
      /** Formato: date-time */
      snapshotAt: string;
      /** Formato: int64 */
      totalElements: number;
      /** Formato: int32 */
      totalPages: number;
    };
    OrderSummary: {
      courierId?: string | null;
      /** Formato: date-time */
      createdAt: string;
      dropoff: components['schemas']['GeoPoint'];
      /** Formato: uuid */
      id: string;
      publicCode: string;
      searchingSince: (null | string) | null;
      /** Formato: date-time */
      slaDeadline: string;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      store: components['schemas']['StoreView'];
      /** Formato: int64 */
      version: number;
    };
    OrderView: {
      confirmationCode?: string | null;
      courierId?: string | null;
      /** Formato: date-time */
      createdAt: string;
      dropoff: components['schemas']['GeoPoint'];
      /** Formato: uuid */
      id: string;
      publicCode: string;
      /** Formato: date-time */
      slaDeadline: string;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      store: components['schemas']['StoreView'];
      timeline: components['schemas']['OrderEventView'][];
      /** Formato: int64 */
      version: number;
    };
    PickUpRequest: {
      /** Formato: int64 */
      expectedVersion: number;
    };
    PositionBatchRequest: {
      positions: components['schemas']['PositionSample'][];
    };
    PositionReceipt: {
      /** Formato: int32 */
      accepted: number;
      /** Formato: date-time */
      flushDeadline: string;
      /** Formato: int32 */
      ignored: number;
      /** Formato: date-time */
      latestRecordedAt: string;
    };
    PositionSample: {
      position: components['schemas']['GeoPoint'];
      /** Formato: date-time */
      recordedAt: string;
    };
    ReassignOrderRequest: {
      /** Formato: int64 */
      expectedVersion: number;
    };
    SessionView: {
      authenticated: boolean;
      user: components['schemas']['DemoUserView'] | null;
    };
    SimulationConfig: {
      /** Formato: double */
      simulationSpeed: number;
    };
    StoreView: {
      /** Formato: uuid */
      id: string;
      location: components['schemas']['GeoPoint'];
      name: string;
      /** Formato: uuid */
      serviceAreaId: string;
    };
    TrackingEvent: {
      fromStatus:
        | ('ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING' | null)
        | null;
      /** Formato: date-time */
      occurredAt: string;
      /** Formato: int64 */
      orderVersion: number;
      /** @enum {string} */
      toStatus: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      type: string;
    };
    TrackingRoute: {
      /** Formato: date-time */
      calculatedAt: string;
      /** @enum {string} */
      destination: 'DROPOFF' | 'PICKUP';
      /** Formato: double */
      distanceMeters: number;
      /** Formato: int64 */
      durationMillis: number;
      geometry: components['schemas']['GeoPoint'][];
      /** Formato: int64 */
      simulatedDurationMillis: number;
    };
    TrackingSnapshot: {
      courierPosition: components['schemas']['GeoPoint'] | null;
      courierPositionUpdatedAt: (null | string) | null;
      /** Formato: date-time */
      createdAt: string;
      dropoff: components['schemas']['GeoPoint'];
      etaSeconds: (number | null) | null;
      pickup: components['schemas']['GeoPoint'];
      route: components['schemas']['TrackingRoute'] | null;
      /** Formato: date-time */
      serverTime: string;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      storeName: string;
      timeline: components['schemas']['TrackingEvent'][];
      /** Formato: int64 */
      version: number;
    };
    TrainingOrderReceipt: {
      confirmationCode: string;
      /** Formato: date-time */
      expiresAt: string;
      /** Formato: uuid */
      offerId: string;
      /** Formato: uuid */
      orderId: string;
      /** Formato: date-time */
      serverTime: string;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      /** Formato: int64 */
      version: number;
    };
    TrainingOrderState: {
      /** Formato: uuid */
      orderId: string;
      /** Formato: date-time */
      serverTime: string;
      /** @enum {string} */
      status: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      /** Formato: int64 */
      version: number;
    };
  };
  responses: never;
  parameters: never;
  requestBodies: never;
  headers: never;
  pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
  getCsrf: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CsrfView'];
        };
      };
    };
  };
  enterDemo: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DemoLogin'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['SessionView'];
        };
      };
      /** @description Confira o papel informado. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Limite de entradas atingido. */
      429: {
        headers: {
          /** @description Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  logoutDemo: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description No Content */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getSession: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['SessionView'];
        };
      };
    };
  };
  setCourierAvailability: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['AvailabilityRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CourierView'];
        };
      };
      /** @description Confira a disponibilidade e a posição informadas. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Uma oferta ou entrega impede esta mudança de disponibilidade. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A posição deve ficar dentro da área atendida e próxima a uma via acessível. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O grafo de roteamento ainda não está disponível. */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getCurrentCourier: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CourierView'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getCourierOffer: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CourierOfferInbox'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  submitCourierPositions: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PositionBatchRequest'];
      };
    };
    responses: {
      /** @description Accepted */
      202: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['PositionReceipt'];
        };
      };
      /** @description Confira as posições e os horários do lote. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Fique online antes de enviar posições. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description As posições devem ficar dentro da área atendida e próximas a vias acessíveis. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Limite de envio de posições atingido. */
      429: {
        headers: {
          /** @description Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O roteamento ou a gravação de posições está temporariamente indisponível. */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getCourierRoute: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CourierRouteView'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Não há entrega ativa com posição recente para calcular a rota. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Não há rota de moto disponível entre os pontos. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O grafo de roteamento ainda não está disponível. */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  createCourierTrainingOrder: {
    parameters: {
      query?: never;
      header: {
        'Idempotency-Key': string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Created */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TrainingOrderReceipt'];
        };
      };
      /** @description Confira a chave de idempotência. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A intenção de treino ou o estado e a posição do entregador mudaram. Consulte seu trabalho novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Não há rota de treino disponível dentro da área atendida. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Limite de criação ou de pedidos ativos atingido. */
      429: {
        headers: {
          /** @description Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O despacho está em atualização ou o roteamento está indisponível. */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  cancelCourierTrainingOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CancelTrainingOrderRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['TrainingOrderState'];
        };
      };
      /** @description Confira o pedido e sua versão. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O pedido não existe ou não está visível neste contexto. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A versão ou a etapa do pedido mudou. Consulte o pedido novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getCourierWork: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['CourierWorkView'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getMapConfig: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['SimulationConfig'];
        };
      };
    };
  };
  getPublicMapSnapshot: {
    parameters: {
      query: {
        east: number;
        north: number;
        south: number;
        west: number;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['MapFrame'];
        };
      };
      /** @description Confira os limites da área visível. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  acceptOffer: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Ação concluída. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      /** @description Confira o identificador da oferta. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A oferta não existe ou não pertence ao entregador autenticado. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A oferta encerrou ou o pedido e o entregador mudaram. Consulte seu trabalho novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  rejectOffer: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description Ação concluída. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      /** @description Confira o identificador da oferta. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A oferta não existe ou não pertence ao entregador autenticado. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A oferta encerrou ou o pedido e o entregador mudaram. Consulte seu trabalho novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getOperationsMetrics: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OperationsMetrics'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  reassignOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['ReassignOrderRequest'];
      };
    };
    responses: {
      /** @description Ação concluída. */
      204: {
        headers: {
          [name: string]: unknown;
        };
        content?: never;
      };
      /** @description Confira o pedido e sua versão. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O pedido não existe ou não está visível neste contexto. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A versão ou a etapa do pedido mudou. Consulte o pedido novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getOperationsSnapshot: {
    parameters: {
      query: {
        east: number;
        north: number;
        south: number;
        west: number;
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['MapFrame'];
        };
      };
      /** @description Confira os limites da área visível. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  listOrders: {
    parameters: {
      query?: {
        code?: string;
        page?: number;
        scope?: 'ALL' | 'OWN' | 'PUBLIC';
        size?: number;
        snapshotAt?: string;
        sort?: 'RECENT' | 'WAITING';
        status?: 'ASSIGNED' | 'AT_STORE' | 'CANCELED' | 'DELIVERED' | 'PICKED_UP' | 'SEARCHING';
      };
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['OrderPage'];
        };
      };
      /** @description Confira a paginação, o estado, o código, o escopo, a ordenação e o instante da consulta. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  createOrder: {
    parameters: {
      query?: never;
      header: {
        'Idempotency-Key': string;
      };
      path?: never;
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CreateOrderRequest'];
      };
    };
    responses: {
      /** @description Pedido criado ou repetido pela chave de idempotência. */
      201: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/json': components['schemas']['OrderView'];
        };
      };
      /** @description Confira o destino e a chave de idempotência. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A chave de idempotência já foi usada para outro destino. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O destino fica fora da área atendida ou não possui rota de moto. */
      422: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Limite de criação ou de pedidos ativos atingido. */
      429: {
        headers: {
          /** @description Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O grafo de roteamento ainda não está disponível. */
      503: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['OrderView'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O recurso não existe ou não está visível neste contexto. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  cancelOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['CancelOrderRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['OrderView'];
        };
      };
      /** @description Confira o pedido e sua versão. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O pedido não existe ou não está visível neste contexto. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A versão ou a etapa do pedido mudou. Consulte o pedido novamente. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  deliverOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['DeliverRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['AssignedOrderView'];
        };
      };
      /** @description Confira o pedido, a versão e o código informado. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A entrega não pertence ao entregador autenticado. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Confira a etapa, a versão, a posição e o código da entrega. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Limite de tentativas do código de confirmação atingido. */
      429: {
        headers: {
          /** @description Segundos até uma nova tentativa, quando o bloqueio tem prazo conhecido. */
          'Retry-After'?: number;
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  pickUpOrder: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        id: string;
      };
      cookie?: never;
    };
    requestBody: {
      content: {
        'application/json': components['schemas']['PickUpRequest'];
      };
    };
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['AssignedOrderView'];
        };
      };
      /** @description Confira o pedido, a versão e o código informado. */
      400: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O token CSRF ou o papel não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description A entrega não pertence ao entregador autenticado. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description Confira a etapa, a versão, a posição e o código da entrega. */
      409: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getCurrentStore: {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['StoreView'];
        };
      };
      /** @description Entre na demonstração para continuar. */
      401: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O papel ou a sessão não permite esta ação. */
      403: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
      /** @description O recurso não existe ou não está visível neste contexto. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
  getTracking: {
    parameters: {
      query?: never;
      header?: never;
      path: {
        code: string;
      };
      cookie?: never;
    };
    requestBody?: never;
    responses: {
      /** @description OK */
      200: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          '*/*': components['schemas']['TrackingSnapshot'];
        };
      };
      /** @description O link de rastreio não existe. */
      404: {
        headers: {
          [name: string]: unknown;
        };
        content: {
          'application/problem+json': components['schemas']['ApiProblem'];
        };
      };
    };
  };
}
