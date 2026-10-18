# 0002. Publicação gradual em uma VM

Status: aceita.

## Contexto

O ambiente precisa exercitar concorrência entre duas instâncias da API e permitir atualização
gradual dentro de uma única VM. Reiniciar o proxy a cada troca da web interromperia conexões
e invalidaria a continuidade que se pretende medir.

## Decisão

Manter duas APIs atrás de um processo Caddy persistente. Antes de substituir uma API, removê-la
do balanceamento por reload da configuração, aguardar a drenagem e esperar a readiness da nova
instância. A outra API continua atendendo durante esse intervalo.

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
automática e manual. O rollback troca imagens e arquivos estáticos, preservando os dados e a
versão do banco. Migrações precisam ser compatíveis com duas versões da API em execução.

Assets, imagens e bundles do ponto de recuperação precisam permanecer disponíveis. A VM e o
banco continuam sendo pontos únicos de falha. Conexões WebSocket poderão reconectar durante
alterações de configuração e precisarão recuperar o estado quando forem implementadas.
