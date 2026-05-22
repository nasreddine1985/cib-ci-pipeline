# cib-ci-pipeline

Jenkins Shared Library that standardizes the CI/CD process for all CIB Java/Maven repositories.
GitHub: `nasreddine1985/cib-ci-pipeline`

## What it does

Exposes a single `cibPipeline {}` function. Consuming projects add a `Jenkinsfile` + `.cib-ci.yml`
to their repo root — the correct pipeline flow is selected automatically from the build context.

## Project layout

```
vars/cibPipeline.groovy     ← single entry point, all pipeline logic lives here
docs/examples/
  ├── .cib-ci.yml           ← annotated config template
  ├── Jenkinsfile           ← minimal consumer Jenkinsfile
  └── .tool-versions        ← java / maven version pins
test/
  ├── Jenkinsfile.local     ← used by run-local.sh for local testing
  ├── run-local.sh          ← Jenkinsfile Runner test script (Docker required)
  └── plugins.txt           ← Jenkins plugins needed by the runner
```

## Pipeline flows (auto-detected)

| Context | Stages |
|---------|--------|
| PR | Prepare → Build → Verify (Sonar) → Package |
| dev push | + Publish Dev → Docker Dev |
| `labels/*` or `analytics/*` branch | + NeuralD (OWASP) → Fortify → Publish Dev → Docker Dev |
| Manual `CONFIGURE_DEV` | dev flow + Configure Git → Create Release Branch → Increase Dev Version |
| Manual `RELEASE` | security flow + Publish Release → Docker Release → Configure Git → Tag Version → Increase Release Version |

## Key implementation details (vars/cibPipeline.groovy)

- **Tool versions** default to `java: jdk-21`, `maven: 3.9.3` — overridden by `.tool-versions`
- **ciConfig** is loaded from `.cib-ci.yml` via `readYaml` in the Init stage
- **Flow detection** sets `CIB_IS_PR`, `CIB_IS_SEC_BRANCH`, `CIB_PIPELINE_TYPE` env vars used in `when {}` expressions
- **Credential backends**: Jenkins credential vault OR CyberArk — both supported per secret type
- **Helpers**: `_getMavenVersion`, `_deployToArtifactory`, `_buildAndPushDocker`, `_gitPush`

## Consuming project requirements

A project using this library must have at the repo root:
1. `Jenkinsfile` — calls `@Library("cib-ci-pipeline") _` then `cibPipeline {}`
2. `.cib-ci.yml` — secrets config (Artifactory, Sonar, Git SSH, Docker registry)
3. `pom.xml` — Maven project with `target/*.jar` as output
4. `Dockerfile` — copies `target/*.jar`, used by Docker stages

Optional: `.tool-versions` to pin `java` and `maven` versions.

## Sample consumer project

`cib-sample-app` at `/Users/n.abassi/cib-sample-app` (GitHub: `nasreddine1985/cib-sample-app`)
— Spring Boot 3.2.5 / Java 21 REST API used to test this pipeline end-to-end.

## Local testing

```bash
./test/run-local.sh              # simulate PR
./test/run-local.sh dev          # simulate dev push
./test/run-local.sh RELEASE      # simulate release flow
```

Requires Docker. Stages that call external services (Sonar, Artifactory, Docker registry) will
fail locally — that is expected. Goal is to verify correct stage selection per flow.

## Jenkins setup required

- Library registered in **Jenkins → Manage Jenkins → Global Pipeline Libraries** as `cib-ci-pipeline`
- Plugins: Pipeline Maven Integration, SonarQube Scanner, Dependency-Check, Artifactory
- Fortify SCA installed on agents that run security/release flows