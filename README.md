# cib-ci-pipeline

Jenkins Shared Library that standardizes the CI process for all **CIB Java / Maven** repositories hosted on BitBucket.

It exposes a single `cibPipeline` function. Each consuming project adds two files to its repository root and the correct pipeline flow runs automatically based on the trigger (PR, branch push, or manual execution).

---

## Pipeline Flows

![Pipeline flows](docs/pipeline-flows.png)

Five flows are implemented, selected automatically from the build context:

| Flow | Trigger | Stages |
|------|---------|--------|
| **PR** | BitBucket pull request | Prepare → Build → Verify → Package |
| **Dev push** | Push to `dev` branch | + Publish Dev → Docker Dev |
| **Security branch push** | Push to `labels/*` or `analytics/*` | + NeuralD → Fortify → Publish Dev → Docker Dev |
| **Configure Dev** | Manual — `PIPELINE_TYPE=CONFIGURE_DEV` | Dev flow + Configure Git → Create Release Branch → Increase Dev Version |
| **Release** | Manual — `PIPELINE_TYPE=RELEASE` | Security flow + Publish Release → Docker Release → Configure Git → Tag Version → Increase Release Version |

### Stage reference

| Stage | Tool | Description |
|-------|------|-------------|
| Prepare | Maven | `mvn validate dependency:resolve` |
| Build | Maven | `mvn compile` |
| Verify | SonarQube | `mvn sonar:sonar` — static analysis & quality gate |
| Package | Maven | `mvn package` — produces the JAR/WAR |
| NeuralD | OWASP | `dependency-check` — third-party vulnerability scan |
| Fortify | Fortify SCA | SAST scan — source code security analysis |
| Publish Dev | Artifactory | Deploy snapshot to `libs-snapshot-local` |
| Publish Release | Artifactory | Deploy release to `libs-release-local` |
| Docker Dev | Docker | Build & push image tagged `{branch}-{build}` |
| Docker Release | Docker | Build & push image tagged with version and `latest` |
| Configure Git | Git | Set CI bot identity for automated commits |
| Create Release Branch | Git | Branch `release/{version}` from current state |
| Tag Version | Git | Annotated tag `v{version}` |
| Increase Dev Version | Maven | Bump patch SNAPSHOT version in `pom.xml` |
| Increase Release Version | Maven | Bump minor SNAPSHOT version in `pom.xml` |

---

## Jenkins Administrator Setup

Complete these steps once per Jenkins instance before any project can use `cibPipeline`.

### 1 — Required plugins

Install the following plugins via **Manage Jenkins → Plugins → Available**:

| Plugin | Purpose |
|--------|---------|
| Pipeline Maven Integration | `withMaven` step used by every stage |
| Git | SCM checkout |
| Credentials Binding | Inject secrets into build steps |
| SSH Credentials | Store Git SSH keys |
| Pipeline Utility Steps | `readYaml` used in the Init stage |
| JUnit | Publish test reports in post action |
| Workspace Cleanup | `cleanWs()` used in post action |

> The **workflow-aggregator** (Pipeline) plugin must already be present — it ships with most Jenkins distributions.

---

### 2 — Configure JDK tool

Go to **Manage Jenkins → Tools → JDK installations → Add JDK**.

| Field | Value |
|-------|-------|
| Name | `jdk-21` |
| JAVA_HOME | Path to JDK 21 on the agent (e.g. `/opt/java/openjdk`) |

> The name must match the `java` entry in the project's `.tool-versions` file (default: `jdk-21`).  
> If your agents have JDK 21 pre-installed, point directly to its path. To auto-install, use the Adoptium installer and set the version to `jdk-21.0.x+y`.

---

### 3 — Configure Maven tool

Go to **Manage Jenkins → Tools → Maven installations → Add Maven**.

| Field | Value |
|-------|-------|
| Name | `3.9.3` |
| Install automatically | ✅ checked — version `3.9.3` |

> The name must match the `maven` entry in `.tool-versions` (default: `3.9.3`).

---

### 4 — Register the shared library

Go to **Manage Jenkins → System → Global Pipeline Libraries → Add**.

| Field | Value |
|-------|-------|
| Name | `cib-ci-pipeline` |
| Default version | `main` |
| Allow version override | ✅ checked |
| Source | Modern SCM → Git |
| Repository URL | `https://github.com/nasreddine1985/cib-ci-pipeline.git` (or your internal mirror) |
| Credentials | Leave blank for public repo; add a credential for private |

Save. Jenkins will resolve `@Library("cib-ci-pipeline")` from this source.

---

### 5 — Create a pipeline job for a project

1. **New Item → Pipeline**, name it after your project (e.g. `cib-sample-app`).
2. Under **Pipeline → Definition**, choose **Pipeline script from SCM**.
3. Fill in:

| Field | Value |
|-------|-------|
| SCM | Git |
| Repository URL | Your project's repository URL |
| Branch | `*/main` (or `*/dev`) |
| Script Path | `Jenkinsfile` |
| Lightweight checkout | Uncheck if the repo requires authentication |

4. Under **This project is parameterised**, add:
   - **Choice Parameter** — name `PIPELINE_TYPE`, choices: `AUTO`, `CONFIGURE_DEV`, `RELEASE`
   - *(Optional)* **String Parameter** — name `CHANGE_ID`, default empty — set to any non-empty value (e.g. `PR-1`) to simulate a pull request run locally

5. Click **Save** then **Build with Parameters** to run.

---

### 6 — (Optional) Configure SonarQube

If the Verify stage is needed:

1. Install the **SonarQube Scanner** plugin.
2. Go to **Manage Jenkins → System → SonarQube servers → Add**.
3. Set the server URL and add a **Secret Text** credential containing the SonarQube token.
4. In the project's `.cib-ci.yml`, set `secrets.sonar.jenkins.name` to that credential ID.

If `secrets.sonar.jenkins.name` is blank and `SONAR_TOKEN` is not set, the Verify stage is skipped automatically.

---

## Prerequisites

Before onboarding a project, ensure the following are in place:

- A Jenkins instance with the steps above completed
- Credentials for the tools used by your project (see [Secrets Configuration](#secrets-configuration))
- Docker installed on the Jenkins agent (for Docker stages)
- Fortify SCA installed on the Jenkins agent (for security branches and release)

---

## Onboarding

### Step 1 — Create `.cib-ci.yml`

Create this file at the **repository root**. It must be UTF-8 encoded and named exactly `.cib-ci.yml`.

```yaml
template: mvn

secrets:
  artifactory:
    cyberark:
      name: ""        # CyberArk account name — leave blank if using Jenkins credentials
    jenkins:
      name: ""        # Jenkins credential ID (username+password) — used if cyberark.name is blank

  sonar:
    jenkins:
      name: ""        # Jenkins credential ID (secret text) for SonarQube token
      type: token

  git_ssh:
    jenkins:
      name: ""        # Jenkins credential ID (SSH private key) for Git push operations
      type: ssh-key

docker:
  registry: "registry.cib.echonet"   # Docker registry — override with your team's registry
```

Only fill in the secrets that apply to your team (CyberArk **or** Jenkins vault, not both).

See the full annotated template in [`docs/examples/.cib-ci.yml`](docs/examples/.cib-ci.yml).

---

### Step 2 — Create `Jenkinsfile`

Create this file at the **repository root**. It must be UTF-8 encoded and named exactly `Jenkinsfile`.

```groovy
@Library("cib-ci-pipeline") _
cibPipeline {
}
```

See [`docs/examples/Jenkinsfile`](docs/examples/Jenkinsfile).

---

### Step 3 — Configure the BitBucket Webhook

1. Open your BitBucket repository → **Repository Settings → Hooks**
2. Select the **Jenkins** hook (listed under pre-configured hooks)
3. Fill in:
   - **Jenkins URL**: `https://jenkins.cib.echonet/{shortname}-{dev|aps}.{shortname}/`
   - **Repository URL**: the SSH URL of your BitBucket repository
   - **Skip SSL Certificate Validation**: checked

---

### Step 4 (optional) — Pin tool versions

Create `.tool-versions` next to the root `pom.xml`:

```
java jdk-21
maven 3.9.3
```

If this file is absent, the pipeline defaults to `jdk-21` and Maven `3.9.3`.

See [`docs/examples/.tool-versions`](docs/examples/.tool-versions).

---

## Secrets Configuration

The pipeline supports two credential backends. Choose one per secret type.

### CyberArk (recommended for production)

Provide the **CyberArk account name** in `.cib-ci.yml`. The CyberArk Jenkins plugin retrieves the credential at runtime.

```yaml
secrets:
  artifactory:
    cyberark:
      name: "CIB_ARTIFACTORY_PROD"
```

### Jenkins Credential Vault

Provide the **Jenkins credential ID** (the ID you gave when storing the credential in Jenkins).

```yaml
secrets:
  artifactory:
    jenkins:
      name: "artifactory-myteam-creds"
```

### SonarQube token

Generate a **User Token** in SonarQube (**My Account → Security → Generate Token**), store it in Jenkins as a **Secret Text** credential, then reference its ID:

```yaml
secrets:
  sonar:
    jenkins:
      name: "sonarqube-token-myteam"
      type: token
```

The pipeline injects it as `-Dsonar.token` automatically.

### Git SSH key

Used by the pipeline to push commits (version bumps) and tags back to BitBucket.

1. Generate an SSH key pair dedicated to the CI bot.
2. Add the **public key** to a BitBucket service account.
3. Store the **private key** in Jenkins as an **SSH Username with private key** credential.
4. Reference its ID:

```yaml
secrets:
  git_ssh:
    jenkins:
      name: "bitbucket-ci-ssh-key"
      type: ssh-key
```

---

## PIPELINE_TYPE Parameter

By default the pipeline type is `AUTO` — detected from the build context. For manual flows, trigger the Jenkins job with a `PIPELINE_TYPE` parameter:

| Value | When to use |
|-------|-------------|
| `AUTO` | All automatic triggers (PR, branch push) |
| `CONFIGURE_DEV` | Prepare a release: create the release branch and bump the dev version |
| `RELEASE` | Publish a release artifact, push the Docker release image, and tag the version |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `'.cib-ci.yml' not found` | File missing or not at repo root | Create the file following Step 1 |
| `withMaven step not found` | Maven Pipeline Plugin not installed on Jenkins | Install the **Pipeline Maven Integration** plugin |
| SonarQube stage fails with 401 | Wrong or expired token | Regenerate the SonarQube token and update the Jenkins credential |
| Git push fails in version bump stage | SSH key not trusted by BitBucket | Verify the public key is added to the BitBucket service account |
| Docker push fails | Agent not logged in to registry | Run `docker login registry.cib.echonet` on the Jenkins agent or configure a Docker credentials binding |
| Fortify `sourceanalyzer` not found | SCA not installed on agent | Install Fortify SCA on the Jenkins build agent or use a dedicated Fortify agent label |
