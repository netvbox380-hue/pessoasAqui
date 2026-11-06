# PessoasAqui v1.3.2 — Manifesto de rastreabilidade e homologação física

Data-base: 06/11/2026. **Não substitui resultado de testes reais.**

## Correção de identificação da homologação Antigravity

O texto enviado no relatório registrou SHA inexistente
`7e9462ddcf0706222f77839358fc5427dcfd73ba`.
A consulta direta ao GitHub confirmou para o build testado o commit:
`7e9462df48ccd2c74dc9113de12a7db61535ac9a`
(com mensagem `test(modes): route verified network payloads in TemporaryProximitySessionTest`).
O SHA do relatório precisa ser retificado, sem reescrever o histórico.

Qualquer commit posterior, inclusive **somente documentação**, altera o HEAD.
Registrar separadamente **commit exato do código usado no build** e **HEAD
atual do Git**; não declarar que um binário existente foi criado a partir de
um HEAD mais recente sem evidência de rebuild.

## Artefatos declarados pelo relatório — hash ainda não conferido independentemente

| Artefato | SHA-256 declarado pelo Antigravity |
| --- | --- |
| `app-debug-1.3.2.apk` | `28C79666A3FFA4A6FFE478BE4CFC714ABEBE41B2138999A0C291E3974B7776CA` |
| `app-release-1.3.2.aab` | `D5C7B71013CC627263C8FFAE14417685B0251F188B1D529BE58198567224CF05` |

Não existe cópia dos binários nesta auditoria GitHub. O Antigravity deverá
executar no Windows (substituindo caminhos pelas pastas reais):

```powershell
git fetch origin main
git rev-parse HEAD
git rev-parse origin/main
git status --short
# Se já possui o SHA do commit de build, confirmar que existe:
git cat-file -t 7e9462df48ccd2c74dc9113de12a7db61535ac9a

Get-FileHash -Algorithm SHA256 -LiteralPath "C:\CAMINHO\app-debug-1.3.2.apk"
Get-FileHash -Algorithm SHA256 -LiteralPath "C:\CAMINHO\app-release-1.3.2.aab"
```

Confrontar SHA-256 calculado **byte a byte** com o manifesto do relatório.
Se qualquer valor divergir, não distribuir: investigar e registrar o
hash verdadeiro, commit e origem do binário. Se o projeto for recompilado,
usar novo manifesto com hashes e SHA de build efetivamente usados.
Validar assinatura release com `jarsigner -verify -verbose -certs` (e
conferir certificado oficial por fingerprint independente) e APK com
`apksigner verify --print-certs` no pacote apropriado; assinar com a
keystore legítima, nunca criar chave substituta.

Verificar se `versionCode = 28` é superior ao código já publicado no
Google Play; se não for, fazer atualização versionada e novo build,
**sem publicar automaticamente**.

## Roteiro de teste físico — cinco aparelhos

- A: titular original, identidade/backup de teste V3, contatos e histórico
  de teste. Manter A íntegro, desligar após backup confirmado.
- B: aparelho substituto sem identidade anterior; gera sua própria
  chave P-256, recebe autorização e recupera identidade do A.
- F1: emite QR/LINK **mas fica impedido de liberar parte V3 nesta sessão**.
- F2: segundo aprovador da recuperação; após CLAIM, aprova explicitamente
  a liberação de sua parte.
- F3: libera segunda parte diferente, com autorização independente.

**Somente F2 + F3** liberam partes para reconstrução no cenário F1 emissor.
Testar F1: liberação bloqueada. Ver também
`docs/RECUPERACAO_FAMILIAR_V3_INTEGRACAO_20261105.md`.

### Ordem da execução

1. Preparar backend de **homologação isolado**, PostgreSQL de teste e
   app de teste apontado para esse backend; verificar contrato e TLS.
   Não usar dados/credenciais reais de produção. A backend atual de
   produção pode estar desatualizada: sucesso em unit tests não garante
   funcionamento no Render existente.
2. Preparar A, F1, F2, F3 e B, cinco identidades/aparelhos distintos.
   Fazer backup V3 e confirmar ACK de todos os três familiares. Guardar
   original A intacto, **sem formatar nem apagar dados reais**.
3. Repetir recuperação com QR e LINK em sessões separadas;
   verificar janela remota, assinatura do novo aparelho, contestação e
   bloqueio de reutilização.
4. Tentar liberar parte com F1: deve falhar. Liberar F2+F3:
   reconstrução correta do PIN/segredo V3 e restauro do histórico.
5. Após recuperação, testar Modo B (E2EE internet), revogação do aparelho
   A, permanência dos vínculos autorizados, atualização de chave e
   impossibilidade de receber mensagem com chave anterior.
6. Em Zenfone 9 Android 14 e S24 Android 16, testar Modo A com Wi-Fi,
   dados móveis e internet desligados; descoberta bilateral, chat sem
   convite, distância, temporização/purga, sem gravação permanente.
7. Repetir com F3 offline, partes diferentes, parte corrompida, sessão
   expirada, replay, guardião revogado, restart de apps e PostgreSQL
   indisponível; falhar fechado sem destruir dados.
8. Validar economia de bateria, navegação de Radar, permissões, fontes
   ampliadas, chamadas, convites LINK+QR de uso único e responsividade.

Capturar logs e resultados sanitizados (sem PIN, token QR, segredos,
partes Shamir, conversas privadas, chaves privadas ou credenciais).

## Critérios para avançar à produção

- Todos os testes físicos críticos e automatizados concluídos, comprovados.
- SHA exato de código **e** SHA-256 dos binários e assinatura documentados.
- Compatibilidade Android/backend versionada, PostgreSQL com migrações
  aditivas verificadas em homologação, rollback planejado.
- Validar que nova rota de recuperação e WebSocket E2EE não quebram
  clientes já instalados; coordenar atualização backend/aplicativo.
- Quando aprovado, proprietário poderá autorizar deploy Render e
  publicação no Google Play (preferir distribuição interna/fechada antes
  da produção). **Nenhuma autorização implícita**.

**Estado presente:** correção documental efetuada no repositório;
hashes dos binários e homologação física ainda dependem das máquinas
e dos dispositivos sob controle do proprietário.
