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
  '/api/v1/orders': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Listar pedidos visíveis
     * @description Mostra pedidos do contexto do visitante e pedidos públicos das lojas simuladas.
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
    CancelOrderRequest: {
      /** Formato: int64 */
      expectedVersion: number;
    };
    CreateOrderRequest: {
      dropoff: components['schemas']['DropoffRequest'];
    };
    CsrfView: {
      headerName: string;
      token: string;
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
    SessionView: {
      authenticated: boolean;
      user: components['schemas']['DemoUserView'] | null;
    };
    StoreView: {
      /** Formato: uuid */
      id: string;
      location: components['schemas']['GeoPoint'];
      name: string;
      /** Formato: uuid */
      serviceAreaId: string;
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
  listOrders: {
    parameters: {
      query?: {
        page?: number;
        size?: number;
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
      /** @description Confira os parâmetros de paginação. */
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
}
