# Publicação

A configuração de referência para produção usa uma VM Azure com Ubuntu 24.04 arm64,
Docker Compose, PostgreSQL com PostGIS, duas instâncias da API e Caddy.
O domínio previsto é `bora.alexandregaia.me`.
Uma única VM continua sendo um ponto único de falha. A continuidade durante atualizações
é verificada com requisições HTTP reais; conexões WebSocket recuperam suas assinaturas
e consultam um novo snapshot depois da reconexão.

## Conta, VM e DNS

1. Ativar a assinatura Azure for Students e conferir suas regiões permitidas, cotas de CPU e
   disponibilidade de tamanhos antes de criar recursos. Registrar o provedor `Microsoft.Compute`
   se ele ainda não estiver registrado. Cota disponível não garante capacidade na criação.
2. Criar a VM em Canadá Central (`canadacentral`), tamanho `Standard_B2ps_v2`, com 2 vCPUs,
   8 GiB de RAM e imagem Ubuntu Server 24.04 LTS arm64 de geração 2. Usar disco de sistema
   de 64 GiB Standard SSD LRS (`StandardSSD_LRS`) e IPv4 público estático de SKU Standard.
   Configurar acesso por chave SSH; manter a chave privada no computador do proprietário e
   fornecer somente a chave pública de administração à VM.
3. No grupo de segurança de rede (NSG), permitir TCP 80 e 443 para a web e TCP 22 para a
   administração e o runner do GitHub Actions. Os runners hospedados podem trocar de endereço;
   permitir SSH somente para o IP do administrador impede a publicação pelo pipeline.
   A autenticação SSH permanece exclusiva por chave. A porta 5432 do banco e as portas das
   APIs ficam privadas. UDP 443 é opcional para HTTP/3 e exige uma liberação própria no NSG
   e no firewall da VM; o provisionamento atual adiciona somente TCP 80/443.
4. Criar o registro DNS A `bora` apontando para o IPv4 público da VM. Confirmar a resolução
   antes do primeiro deploy; o Caddy obtém e renova o certificado HTTPS automaticamente.

O cadastro e o registro DNS são feitos pelo proprietário com orientação. A configuração de VM
acima é uma proposta de implantação; a criação e o provisionamento precisam ser concluídos
antes de publicar a aplicação. Conferir as regras de entrada na documentação de
[grupos de segurança de rede](https://learn.microsoft.com/en-us/azure/virtual-network/network-security-groups-overview).

## Crédito e custo

Azure for Students oferece US$ 100 de crédito total para uso em até 12 meses. Esse saldo
financia recursos cobrados conforme o uso; não equivale a uma VM permanentemente gratuita.
Manter a assinatura sem conversão para pagamento por uso. Quando o crédito termina, a
assinatura é desabilitada conforme as
[condições da oferta](https://azure.microsoft.com/en-us/pricing/offers/ms-azr-0170p/).

A referência de custo consultada em 18/10/2026 para um mês de 730 horas é:

| Recurso                             | Referência em US$               |
| ----------------------------------- | ------------------------------- |
| VM Linux B2ps_v2                    | 0,0736/h, aproximadamente 53,73 |
| Disco E6 Standard SSD LRS de 64 GiB | 5,28/mês                        |
| IPv4 público Standard               | 0,005/h, aproximadamente 3,65   |
| Total estimado                      | 62,66                           |

Operações de disco e tráfego cobrado não estão incluídos; o consumo real precisa ser acompanhado
no portal. Recalcular os valores antes da criação ou de qualquer mudança de tamanho, região ou
disco: [preço da VM no catálogo Azure](https://prices.azure.com/api/retail/prices?api-version=2023-01-01-preview&%24filter=armSkuName%20eq%20%27Standard_B2ps_v2%27%20and%20armRegionName%20eq%20%27canadacentral%27%20and%20priceType%20eq%20%27Consumption%27%20and%20productName%20eq%20%27Virtual%20Machines%20Bpsv2%20Series%27%20and%20skuName%20eq%20%27B2ps%20v2%27),
[discos gerenciados](https://azure.microsoft.com/en-us/pricing/details/managed-disks/) e
[endereços IP](https://azure.microsoft.com/en-us/pricing/details/ip-addresses/).

Para interromper o consumo de computação durante períodos sem demonstrações, desalocar a VM
pelo Azure e confirmar o estado `Stopped (deallocated)`. Desligar o Ubuntu por SSH mantém a VM
alocada e cobrada. Disco e IPv4 público continuam consumindo crédito enquanto seus recursos
existirem. A aplicação fica indisponível durante a desalocação; ao iniciar novamente, verificar
os containers e o HTTPS. Consultar os
[estados de energia e cobrança](https://learn.microsoft.com/en-us/azure/virtual-machines/states-billing).

## Preparar a VM

Usar chaves diferentes para administração e deploy. Copiar para a VM somente a chave pública
do usuário de deploy e o script [provision.sh](../infra/deploy/provision.sh). Na sessão
administrativa da VM, executar:

```sh
sudo bash provision.sh deploy.pub
```

O script requer uma VM Ubuntu 24.04 arm64 sem uma instalação conflitante de Docker. Ele
confere o fingerprint da chave do repositório Docker, instala Engine e Compose, cria `deploy`,
adiciona a chave pública e configura atualizações de segurança. O grupo Docker permite
administrar o daemon; a chave desse usuário precisa ser protegida como uma credencial administrativa.

As alterações de SSH passam por `sshd -t` antes do reload. A sessão atual permanece aberta:
testar uma segunda conexão como `deploy` antes de encerrar a sessão administrativa. O script
mantém a conta administrativa existente, desabilita autenticação SSH por senha e login direto
como root, e não altera a porta SSH.

O firewall é ajustado por uma unidade idempotente que adiciona somente uma permissão TCP
80/443. As regras, políticas e portas SSH existentes são preservadas. O NSG do Azure e o
firewall da VM precisam permitir o tráfego; configurar um deles não substitui o outro.

O provisionamento gera uma senha aleatória para o banco e as chaves independentes
`BORA_CONFIRMATION_SECRET` e `BORA_SIMULATOR_KEY` em `/opt/bora/env.production`, com permissão
`0600`, sem imprimir esses valores. A execução seguinte preserva o arquivo. Conferir o domínio e o e-mail de
ACME no próprio arquivo, usando um editor na VM. Em instalações anteriores ao M04, adicionar
uma chave aleatória persistente de pelo menos 32 caracteres antes da atualização. Preservar
essa chave nos próximos deploys e no rollback: ela deriva os códigos dos pedidos existentes.

Uma instalação anterior ao simulador também precisa de `BORA_SIMULATOR_KEY` aleatória de pelo
menos 32 caracteres. Ela autoriza somente o cliente interno; as rotas de provisionamento ficam
bloqueadas na origem pública. Manter `SIM_SEED` igual na API e no simulador. A configuração e
os limites estão no [guia de simulação](simulacao.md).

Em produção, `BORA_SESSION_SECURE=true` restringe o cookie de sessão ao HTTPS. As duas APIs
compartilham sessões no PostgreSQL. O Tomcat interpreta os cabeçalhos encaminhados pelo
Caddy usando `SERVER_FORWARD_HEADERS_STRATEGY=native`; o proxy substitui os cabeçalhos de
origem enviados pelo cliente antes de encaminhá-los.

O diretório `/opt/bora/config` usa `0755` e contém somente a configuração pública do Caddy
e os marcadores públicos de tráfego e despacho, em arquivos `0644`. Isso permite que o container sem capabilities leia o bind mount, mesmo
com um UID diferente do usuário de deploy. Credenciais e arquivos de estado ficam fora dele.

## Mapa, CSP e arquivos carregados depois da página

A prévia inicial serve uma base cartográfica real pela própria origem, com dimensões,
prioridade alta e preload no HTML. O raster fica em `/assets/` com hash no nome; os metadados
compilados na interface apontam para essa versão da imagem. Publicar ou restaurar a base junto
do bundle que contém seus limites geográficos evita desalinhamento de pontos após atualizações.

**Explorar mapa** carrega estilo, tiles e recursos de `https://tiles.openfreemap.org`. O
`Content-Security-Policy` de `infra/caddy/Caddyfile` permite somente esse host externo em
`connect-src` e `img-src`, além das origens e tipos já previstos para a aplicação. O worker do
MapLibre e as fontes WOFF2 da interface são servidos pela própria origem. A política é enviada
também na resposta do arquivo do worker, pois ele faz requisições de recursos do mapa.

Manter `script-src 'self'` e a configuração explícita do worker antes de criar o mapa. O CSS
inicial é incorporado ao HTML público pelo build e usa a permissão de estilo inline já presente
na política. Os glifos do mapa pertencem ao estilo externo e são distintos das fontes WOFF2 da
interface. A base externa pode ficar indisponível sem impedir o uso da demonstração de pedidos.

O exportador deve preparar todos os arquivos de `dist/assets`, incluindo a imagem da prévia,
chunks dinâmicos, worker, CSS e fontes, antes de ativar o HTML. Preservar os assets com hash de versões anteriores
permite atender abas que só carreguem um módulo depois da atualização. A rota `/assets/*`
precisa continuar servindo arquivos reais, com tipo MIME correto; um asset ausente deve retornar
404, sem receber o HTML de fallback da aplicação.

Conferir o mapa em uma origem com os headers reais de produção e observar violações de CSP no
console. O ambiente Vite e o Caddy do cluster local não aplicam essa política, portanto um teste
funcional somente nesses ambientes não comprova a configuração de produção. Alterar a CSP muda
o Caddyfile e segue o caminho de reload já descrito neste guia.

Consulte [mapa e interface](mapa-e-interface.md) para comportamento, controles e medições.

## Configurar o GitHub

O workflow de publicação usa imagens no GHCR com a tag do SHA Git completo. Configurar no
repositório os secrets `BORA_SSH_HOST`, `BORA_SSH_KEY` e `BORA_SSH_KNOWN_HOSTS`, usados por
[release.yml](../.github/workflows/release.yml). O usuário SSH é `deploy`; os valores são o host,
a chave privada de deploy e a chave pública do servidor em `known_hosts`. Conferir o fingerprint do
servidor por um canal confiável, como o console da VM; `ssh-keyscan` sozinho não confirma identidade.

O token temporário do workflow chega pela entrada padrão da conexão SSH. O script executa
`docker login` usando um `DOCKER_CONFIG` temporário em `/opt/bora/.registry.*`, com diretório
`0700`, e o apaga ao sair. A credencial não fica em `env.production`, nos argumentos dos
processos ou nos logs da aplicação.

## Atualizar e recuperar

Cada bundle fica em `/opt/bora/releases/<sha>`, preservado junto às versões anteriores.
O arquivo `env.production` contém configuração e credenciais; `state.env` registra as versões
ativas do bundle, das duas APIs, da web e do banco. `previous.env` registra o ponto anterior
à última publicação aprovada; `recovery.env` registra o início da tentativa em andamento.
Uma tentativa que falha preserva o ponto de rollback manual. Esses arquivos ficam com acesso
restrito ao usuário de deploy.

O workflow executa:

```sh
bash /opt/bora/releases/<sha>/infra/deploy/deploy.sh <sha>
```

O deploy adquire um lock `flock`, baixa as imagens e prepara os assets antes de alterar o
tráfego. O script troca atomicamente `/opt/bora/config/upstream-mode`, que contém `api1`,
`api2` ou `both`. O Caddy lê esse modo uma vez por requisição para escolher um dos três
balanceamentos estáticos. O script aguarda a drenagem, troca uma instância e espera a readiness,
repetindo a operação com a segunda API. Só então ativa o HTML da nova web por uma troca
atômica de symlink. Assets com hash de releases anteriores permanecem disponíveis para páginas
já abertas. A publicação normal da aplicação não recarrega o servidor HTTP do Caddy.

Antes de substituir a primeira API, o script grava `disabled` atomicamente em
`/opt/bora/config/dispatch-version`. A criação de ofertas exige nesse arquivo o SHA completo
da própria API e `upstream-mode` igual a `both`. A substituição de cada instância pode voltar
o proxy a `both`; por isso esse modo sozinho não libera o despacho. O marcador de despacho
só abre após o teste público com APIs e web na mesma versão. Um marcador ausente, inválido
ou de outra versão mantém a criação bloqueada. Expiração, cancelamento e respostas às ofertas
persistidas continuam disponíveis.

As APIs montam o diretório de configuração completo somente para leitura. Assim, as trocas
atômicas dos arquivos ficam visíveis sem recriar o bind mount. Na recuperação, o script fecha
primeiro o marcador e só o reabre se o bundle, as duas APIs e a web restaurados coincidirem.
O ambiente local libera a criação explicitamente; produção exige a ativação por versão.

O simulador é interrompido antes da troca de APIs. Ele só volta após as duas APIs, a web, o
teste público e a abertura do despacho na versão de destino. Sua imagem usa o SHA do bundle;
não existe uma versão independente em `state.env`. A retomada preserva as identidades e os
trabalhos dos atores no banco. No rollback para um bundle sem simulador, o processo é removido
e o script aguarda pelo menos 40 segundos desde sua parada antes de liberar o despacho
antigo. Isso permite que suas últimas posições expirem e evita que a versão antiga escolha
um entregador simulado para um pedido privado. A parada é registrada em arquivo restrito
fora do diretório público, inclusive quando uma tentativa é interrompida.

O Caddyfile só é substituído quando seu conteúdo muda. Essa mudança real exige reload e pode
encerrar conexões HTTP ociosas; a recuperação para um bundle com configuração antiga também
pode usar esse caminho. Preservar o processo não elimina esse efeito. A continuidade medida
na atualização de aplicação pressupõe a mesma configuração de proxy.

Antes de substituir o Caddyfile, o deploy registra `.proxy-config-pending` fora do diretório
público. A pendência só é removida após aplicar a configuração ou confirmar que o próximo
início do proxy a lerá. Assim, uma interrupção entre gravar e recarregar não faz uma nova
tentativa confundir o arquivo em disco com a configuração em execução.

A readiness inclui o banco e o grafo carregado e aquecido. O teste de fumaça consulta a página inicial e a readiness pela
origem pública. Se uma etapa falhar, o script mantém o resultado de falha e tenta recuperar
as versões efetivas anteriores, escolhendo primeiro a API que pode ser trocada enquanto a
outra atende. Na primeira publicação ainda não existe uma versão anterior para recuperar.

Para solicitar uma recuperação manual, na VM:

```sh
bash /opt/bora/releases/<sha-ativo>/infra/deploy/rollback.sh
```

O rollback restaura imagens, bundle e HTML anteriores. Ele não desfaz migrações, não troca a
imagem do banco e não apaga dados. Toda mudança de esquema precisa aceitar simultaneamente
as versões anterior e nova da aplicação: adicionar antes de usar, migrar dados e remover
estruturas antigas apenas depois que não forem mais necessárias. Uma migração incompatível
exige um procedimento de manutenção próprio antes da publicação.

Manter pelo menos o bundle, as imagens e os assets do ponto de recuperação. Não executar uma
limpeza geral de imagens ou volumes durante uma publicação. Se a recuperação falhar, consultar
`state.env`, `previous.env`, os logs do Compose e a saúde de cada API antes de tentar novamente.

## Preparar e medir o roteamento

Antes de tocar o tráfego, o deploy executa `graph-import` com a imagem do bundle de destino.
O processo usa até 2 GiB, não tem rede e não inicializa Spring ou o banco. Importa uma vez,
aquece uma rota e publica um diretório imutável por hash em `routing-graphs`. Se falhar, as
APIs e o estado da publicação continuam nas versões anteriores. Bundles anteriores ao
roteamento não possuem esse serviço e são tratados sem importação.

Cada API monta esse volume somente para leitura, dentro do seu limite de 1.536 MiB. O diretório
na imagem pertence ao UID 10001, também usado pelo importador, para inicializar um volume novo.
Preservar os grafos das versões atual e anterior junto aos respectivos bundles e imagens.
O rollback não apaga grafos ou muda a imagem do banco.

Para medir o cálculo na própria VM, como usuário de deploy e com uma publicação concluída:

```sh
bash /opt/bora/releases/<sha-ativo>/infra/deploy/benchmark-routing.sh --warmup=100 --iterations=500 --max-p95-ms=50
```

O script adquire o lock de publicação e inicia uma JVM separada, usando o limite e o volume
somente leitura da API. Não interrompe os processos que atendem o público. O relatório mede
rota e ETA dentro da JVM; a latência pela internet deve ser medida separadamente.
Consulte o [guia de roteamento](roteamento.md) para dados, perfil e interpretação das medições.

## Verificar o mecanismo localmente

Os testes usam um projeto Compose, um registry local, tags de 40 caracteres e volumes exclusivos.
Constroem uma imagem inválida da API para verificar o rollback automático. A origem consultada
é o próprio Caddy HTTP na rede isolada de testes; o teste não valida DNS ou emissão de certificado.

Com Docker ativo, preparar as quatro imagens e executar:

```sh
docker build -t bora-dev-api:latest -f apps/api/Dockerfile .
docker build -t bora-web:m01-assets -f apps/web/Dockerfile.prod .
docker build -t bora-simulator:dev -f apps/simulator/Dockerfile .
docker build -t bora-postgis:18.6-3.6.4 infra/postgres
bash infra/deploy/test.sh
```

O mesmo launcher pode receber `BORA_TEST_API_IMAGE`, `BORA_TEST_WEB_IMAGE`,
`BORA_TEST_DB_IMAGE` e `BORA_TEST_SIMULATOR_IMAGE` para usar as imagens já construídas pelo CI.
As variáveis opcionais
`BORA_TEST_PREVIOUS_API_IMAGE` e `BORA_TEST_PREVIOUS_WEB_IMAGE` permitem fornecer imagens
efetivamente diferentes para a versão anterior. Ele prepara o executor Linux,
testa bootstrap, atualização, recuperação automática e rollback manual, e remove os containers e volumes do
projeto de teste. Nenhum recurso do ambiente de desenvolvimento é usado como destino.

O resultado informa quantas requisições receberam HTTP 200 durante a atualização e a falha
simulada. O teste usa também um cliente Node com conexões persistentes e verifica que o
servidor HTTP não foi recarregado durante as trocas normais. O cliente também entra na
demonstração e exige que a mesma identidade continue autenticada entre as duas APIs e após
os rollbacks. Esse resultado descreve as
condições exercitadas no teste, sem garantir disponibilidade absoluta da VM ou do provedor.
Por padrão, as tags válidas do cenário usam a mesma imagem de origem; a prova de sessão cobre
a troca de processos. Quando as variáveis de versão anterior são fornecidas, o mesmo ensaio
troca as imagens efetivas. A compatibilidade entre versões diferentes também depende das migrações
aditivas e da validação da atualização real.

Na validação local de 18/10/2026 em amd64, com imagens anteriores e novas distintas, o cliente
Node completou 17.581 requisições em 300 segundos, sem falhas de HTTP, conexão ou identidade.
Outras 2.786 consultas curl receberam
HTTP 200 durante a atualização, a falha simulada e os rollbacks automático e manual.
O teste também confirmou o
lock de concorrência, a repetição idempotente de uma release, a falha de download sem perder
o rollback anterior e a preservação dos containers do Caddy e do banco.
Durante a troca, 82 consultas confirmaram ausência de ofertas com o marcador fechado; 41 delas
observaram a API nova saudável. Depois da ativação, a oferta foi criada. O rollback para as
imagens anteriores preservou o prazo de outra oferta, que expirou e liberou o entregador pela
varredura da versão restaurada.

## Monitorar

Após confirmar o HTTPS, configurar no UptimeRobot os monitores HTTP da página inicial e de
`https://bora.alexandregaia.me/actuator/health/readiness`, com os avisos no e-mail do proprietário.
Conferir as opções e os intervalos disponíveis na conta no momento da configuração.

Conferir o espaço de disco antes e depois de publicar uma versão:

```sh
df -h /
docker system df
```

O build das imagens acontece no CI; a VM baixa as imagens finais e não precisa guardar o JDK
ou o cache Maven. Duas instâncias da mesma imagem compartilham suas camadas. Releases, assets
antigos e imagens continuam ocupando espaço ao longo do tempo. Antes de remover uma versão,
conferir `state.env` e `previous.env`, preservando o bundle, as imagens e os assets atuais e do
ponto de rollback, além da imagem e do volume do banco. Não executar `docker system prune`,
limpezas gerais de volumes ou remoções durante uma publicação. O crescimento do grafo e dos
dados de demonstração precisa ser acompanhado a cada atualização.

A B2ps_v2 usa créditos de CPU e tem desempenho base de 40%; as 2 vCPUs não representam
capacidade integral contínua. Na validação do roteamento, registrar créditos disponíveis,
memória e tempo de importação do GraphHopper antes de avaliar carga sustentada. Os limites de
memória dos containers precisam comportar o grafo e o restante da aplicação simultaneamente.
Consultar as [características da série Bpsv2](https://learn.microsoft.com/en-us/azure/virtual-machines/sizes/general-purpose/bpsv2-series).

Referências: [Docker no Ubuntu](https://docs.docker.com/engine/install/ubuntu/),
[atualização de configuração do Caddy](https://caddyserver.com/docs/command-line#caddy-reload)
e [Docker Compose up](https://docs.docker.com/reference/cli/docker/compose/up/).
