# 0002. Publicação gradual em uma VM

Status: aceita.

## Contexto

O ambiente precisa exercitar concorrência entre duas instâncias da API e permitir atualização
gradual dentro de uma única VM. Reiniciar o proxy a cada troca da web interromperia conexões
e invalidaria a continuidade que se pretende medir.

Medições públicas identificaram encerramentos de conexões HTTP persistentes
durante reloads do Caddy, mesmo mantendo seu processo. Um teste com as APIs imóveis reproduziu
o problema ao alternar apenas os upstreams por reload. Preservar o container não basta para
preservar essas conexões.

## Decisão

Manter duas APIs atrás de um processo Caddy persistente. Antes de substituir uma API, removê-la
do balanceamento por um marcador público trocado atomicamente, aguardar a drenagem e esperar
a readiness da nova instância. A outra API continua atendendo durante esse intervalo.

O Caddy lê o modo `api1`, `api2` ou `both` uma vez por requisição e escolhe entre três
handlers de proxy com upstreams estáticos e health checks. A troca normal de aplicação
não recarrega o servidor HTTP. Uma alteração real do próprio Caddyfile ainda exige reload;
a recuperação para bundles antigos mantém o procedimento legado quando necessário.

Publicar a web como artefato estático em uma imagem versionada. Preparar cada release em um
diretório próprio, preservar assets com hash em armazenamento compartilhado e ativar o HTML
por troca atômica de symlink. O proxy usa a mesma montagem de volume em todas as versões.

Usar tags com o SHA completo do commit para as imagens da aplicação. Registrar as versões
efetivas de cada instância, da web, do bundle e do banco. A imagem do banco é definida na
primeira publicação e preservada nas atualizações da aplicação.

As credenciais do GHCR chegam pela entrada padrão de SSH e ficam apenas no diretório temporário
de autenticação do Docker durante a implantação. A chave de SSH do pipeline é diferente da chave
administrativa. O servidor SSH é identificado por uma chave previamente registrada.

## Consequências

Os testes precisam exercitar requisições reais durante publicação e falhas, incluindo recuperação
automática e manual, com clientes que reutilizam conexões. O rollback troca imagens e arquivos
estáticos, preservando os dados e a versão do banco. Migrações precisam ser compatíveis com
duas versões da API em execução.

Assets, imagens e bundles do ponto de recuperação precisam permanecer disponíveis. A VM e o
banco continuam sendo pontos únicos de falha. Conexões WebSocket podem reconectar durante
a substituição de APIs ou alterações de configuração; os clientes recuperam o estado por
[snapshots autorizados](0005-tempo-real-e-snapshots.md).

Referências: [variáveis por requisição](https://caddyserver.com/docs/caddyfile/directives/vars),
[placeholders de arquivos](https://caddyserver.com/docs/conventions#placeholders) e
[comportamento da parada dos servidores no Caddy 2.11.7](https://github.com/caddyserver/caddy/blob/v2.11.7/modules/caddyhttp/app.go).
