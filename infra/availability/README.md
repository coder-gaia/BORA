# Disponibilidade no Azure Logic Apps

O template `logic-apps.json` cria dois workflows Consumption, com identidade gerenciada própria e estado inicial **Disabled**. O horário é calculado em `E. South America Standard Time`, equivalente a `America/Sao_Paulo` na política do projeto.

- Ligar: segunda a sexta às 09h20, 09h30 e 09h40.
- Desalocar: todos os dias às 22h30, 22h40 e 22h50.
- Disponibilidade anunciada: dias úteis das 10h às 22h. O aquecimento e o encerramento têm margens próprias.

As tentativas adicionais consultam o estado antes de solicitar uma operação. O workflow de ligar também verifica a readiness pública. Uma resposta ARM `202` indica aceite da solicitação; a conclusão exige uma nova consulta `instanceView` com o estado esperado.

## Gerar e verificar

Os horários vêm de `scripts/availability-policy.mjs`. Depois de alterar essa política, gere e revise novamente o JSON:

```sh
node scripts/logic-apps-availability.mjs --write
node scripts/logic-apps-availability.mjs --check
node --test scripts/logic-apps-availability.test.mjs
```

O template recebe `location`, `namePrefix`, `vmResourceId`, `roleDefinitionId` e `readinessUrl`. Não contém IDs de assinatura, senhas ou chaves. `roleDefinitionId` deve identificar o papel existente com apenas estas quatro permissões:

```text
Microsoft.Compute/virtualMachines/read
Microsoft.Compute/virtualMachines/instanceView/read
Microsoft.Compute/virtualMachines/start/action
Microsoft.Compute/virtualMachines/deallocate/action
```

As duas atribuições usam exclusivamente o escopo `vmResourceId`. O template não cria papéis amplos nem recursos de conexão, armazenamento, integração ou Log Analytics. A definição do papel deve ser conferida antes da implantação: receber um ID como parâmetro não comprova suas permissões.

## Ativação e segurança

O template sempre implanta os workflows desabilitados. Primeiro valide o ARM e as definições, confira as identidades e atribuições, aguarde a propagação do RBAC e faça os ensaios autorizados. Habilite o agendamento depois de confirmar os resultados. Uma reimplantação do template volta a deixar os workflows desabilitados.

O `startTime` fixo no passado, junto aos dias, horas e minutos explícitos, evita depender de uma primeira execução imediata ou de um início futuro configurado com pouca antecedência. A recorrência é nativa; não existe polling de um conector externo.

Cada alteração de energia fica diretamente dentro de uma condição que recalcula o relógio atual. Uma execução antiga de ligar não liga a VM à noite ou no fim de semana; uma execução antiga de desligar não desliga a VM durante a janela ativa. Estados de energia desconhecidos impedem a operação. Uma transição já em andamento é apenas acompanhada.

Os POSTs não têm repetição HTTP implícita e usam `DisableAsyncPattern`. Isso impede seguir o `Location` de uma operação ARM para outro escopo e garante que uma nova tentativa nativa refaça a validação do horário. A confirmação usa GET no `instanceView` da própria VM. A identidade não recebe acesso às operações de região, execução de comandos ou exclusão.

## Limites e observação

O polling de energia tem limite de 12 iterações e três minutos; a readiness, oito iterações e dois minutos. Ambos usam `FailWhenLimitsReached`: esgotar o limite não deve produzir uma confirmação falsa. A pausa entre consultas é de 15 segundos. Falhas HTTP de uma tentativa são visíveis no histórico; as recorrências seguintes oferecem novas tentativas.

No Consumption, uma chamada HTTP pode levar até 120 segundos. O limite de um `Until` é verificado entre iterações, portanto uma iteração em andamento pode ultrapassar sua duração configurada. Considerando a consulta inicial, um POST e essas caudas HTTP, a estimativa superior do caminho de ligar é cerca de 13min30s de trabalho e espera, além dos atrasos do próprio serviço. Isso não é uma garantia de execução em 15 minutos.

Os históricos ficam retidos por sete dias. Um estado `running` não confirma saúde da aplicação; a evidência de disponibilidade exige HTTP 200 e corpo com `status: UP`. Uma confirmação pontual de saúde não prova disponibilidade contínua.

Uma estimativa conservadora de ações, incluindo triggers e controles, usa um mês de 31 dias com 23 dias úteis. Considerando duas consultas de energia e quatro de readiness na primeira partida, cada partida usa 43 ações; as duas tentativas seguintes, com a VM saudável, usam 16 cada. O primeiro desligamento usa 22 ações e os dois seguintes, com a VM já desalocada, usam 10 cada. Esse cenário soma **3.027 ações no mês**.

O envelope estrutural, com todos os loops chegando ao número máximo de iterações em todas as recorrências, é **14.886 ações no mês**: até 116 por execução de ligar e 74 por execução de desligar. Esse limite é conservador e inclui caminhos que podem terminar antes. As tentativas nativas e os limites evitam repetição infinita, mas não garantem que um mês com falhas prolongadas permaneça na franquia gratuita. Confira as métricas de execuções e ações, o crédito disponível e os preços vigentes; a estimativa não é uma medição de consumo.

Os dois workflows não compartilham o bloqueio de concorrência do GitHub. A publicação precisa respeitar sua própria janela de início e um tempo máximo que termine antes do desligamento. Não reative simultaneamente outro controlador automático de energia.

Fontes oficiais:

- [Recorrência, horários e início no passado](https://learn.microsoft.com/en-us/azure/logic-apps/concepts-schedule-automated-recurring-tasks-workflows).
- [HTTP, `DisableAsyncPattern` e definição dos controles](https://learn.microsoft.com/en-us/azure/logic-apps/logic-apps-workflow-actions-triggers).
- [Limites dos loops e `FailWhenLimitsReached`](https://learn.microsoft.com/en-us/azure/logic-apps/logic-apps-control-flow-loops).
- [Limites HTTP e retenção do histórico](https://learn.microsoft.com/en-us/azure/logic-apps/logic-apps-limits-and-config).
