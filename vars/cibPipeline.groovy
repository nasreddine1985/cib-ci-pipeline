/**
 * CIB Maven CI Pipeline - shared library entry point.
 *
 * Flows (auto-detected or driven by PIPELINE_TYPE parameter):
 *   AUTO + PR          → Prepare → Build → Verify → Package
 *   AUTO + dev push    → ... → Publish_dev → Docker_dev
 *   AUTO + sec branch  → ... → NeuralD → Fortify → Publish_dev → Docker_dev
 *   CONFIGURE_DEV      → ... → Publish_dev → Docker_dev → Configure_git → Create_release_branch → Increase_dev_version
 *   RELEASE            → ... → NeuralD → Fortify → Publish_release → Docker_release → Configure_git → Tag_version → Increase_release_version
 */
def call(Closure body = {}) {
    Map userConfig = [:]
    if (body) {
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body.delegate = userConfig
        body()
    }

    // Script-level state — captured by closure in when{} expressions within the same build.
    def ciConfig      = [:]
    def toolVersions  = [java: 'jdk-21', maven: '3.9.3']

    pipeline {
        agent any

        parameters {
            choice(
                name: 'PIPELINE_TYPE',
                choices: ['AUTO', 'CONFIGURE_DEV', 'RELEASE'],
                description: 'AUTO: detected from trigger/branch. CONFIGURE_DEV / RELEASE: manual flows.'
            )
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            timestamps()
            disableConcurrentBuilds()
        }

        stages {

            /* ------------------------------------------------------------------ */
            stage('Init') {
                steps {
                    script {
                        checkout scm

                        if (!fileExists('.cib-ci.yml')) {
                            error '.cib-ci.yml not found at repository root — follow onboarding step 1/4'
                        }
                        ciConfig = readYaml file: '.cib-ci.yml'

                        if (fileExists('.tool-versions')) {
                            readFile('.tool-versions').trim().split('\n').each { line ->
                                def parts = line.trim().split('\\s+')
                                if (parts.length == 2) toolVersions[parts[0]] = parts[1]
                            }
                        }

                        // Resolve pipeline context into env vars for when{} expressions.
                        env.CIB_IS_PR             = (env.CHANGE_ID != null).toString()
                        env.CIB_IS_SEC_BRANCH     = (env.BRANCH_NAME ==~ /^(labels|analytics)(\/.*)?$/).toString()
                        env.CIB_PIPELINE_TYPE     = params.PIPELINE_TYPE ?: 'AUTO'

                        echo """\
                            === CIB Pipeline Init ===
                            Branch        : ${env.BRANCH_NAME}
                            Is PR         : ${env.CIB_IS_PR}
                            Sec branch    : ${env.CIB_IS_SEC_BRANCH}
                            Pipeline type : ${env.CIB_PIPELINE_TYPE}
                            Java          : ${toolVersions.java}
                            Maven         : ${toolVersions.maven}
                        """.stripIndent()
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Prepare') {
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh 'mvn validate dependency:resolve --batch-mode'
                        }
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Build') {
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh 'mvn compile --batch-mode -DskipTests'
                        }
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Verify') {
                steps {
                    script {
                        def sonar = ciConfig.secrets?.sonar
                        if (sonar?.jenkins?.name) {
                            withCredentials([string(credentialsId: sonar.jenkins.name, variable: 'SONAR_TOKEN')]) {
                                withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                                    sh 'mvn sonar:sonar --batch-mode -Dsonar.token=$SONAR_TOKEN'
                                }
                            }
                        } else {
                            // CyberArk integration or token already in env
                            withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                                sh 'mvn sonar:sonar --batch-mode'
                            }
                        }
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Package') {
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh 'mvn package --batch-mode -DskipTests'
                        }
                        archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('NeuralD') {
                when {
                    expression {
                        env.CIB_IS_PR == 'false' &&
                        (env.CIB_IS_SEC_BRANCH == 'true' || env.CIB_PIPELINE_TYPE == 'RELEASE')
                    }
                }
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh 'mvn org.owasp:dependency-check-maven:check --batch-mode'
                        }
                        dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Fortify') {
                when {
                    expression {
                        env.CIB_IS_PR == 'false' &&
                        (env.CIB_IS_SEC_BRANCH == 'true' || env.CIB_PIPELINE_TYPE == 'RELEASE')
                    }
                }
                steps {
                    script {
                        sh "sourceanalyzer -b ${env.JOB_BASE_NAME} -clean"
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh "sourceanalyzer -b ${env.JOB_BASE_NAME} mvn compile -DskipTests --batch-mode"
                        }
                        sh "sourceanalyzer -b ${env.JOB_BASE_NAME} -scan -f results.fpr"
                        archiveArtifacts artifacts: 'results.fpr', allowEmptyArchive: true
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Publish Dev') {
                when {
                    expression {
                        env.CIB_IS_PR == 'false' && env.CIB_PIPELINE_TYPE != 'RELEASE'
                    }
                }
                steps {
                    script {
                        _deployToArtifactory(ciConfig, toolVersions, 'libs-snapshot-local')
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Docker Dev') {
                when {
                    expression {
                        env.CIB_IS_PR == 'false' && env.CIB_PIPELINE_TYPE != 'RELEASE'
                    }
                }
                steps {
                    script {
                        def tag = "${env.BRANCH_NAME}-${env.BUILD_NUMBER}".replaceAll('/', '-')
                        _buildAndPushDocker(ciConfig, tag)
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Publish Release') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'RELEASE' }
                }
                steps {
                    script {
                        _deployToArtifactory(ciConfig, toolVersions, 'libs-release-local')
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Docker Release') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'RELEASE' }
                }
                steps {
                    script {
                        def version  = _getMavenVersion(toolVersions)
                        def registry = ciConfig.docker?.registry ?: 'registry.cib.echonet'
                        def image    = "${registry}/${env.JOB_BASE_NAME}"
                        sh "docker build -t ${image}:${version} ."
                        sh "docker push ${image}:${version}"
                        sh "docker tag ${image}:${version} ${image}:latest"
                        sh "docker push ${image}:latest"
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Configure Git') {
                when {
                    expression { env.CIB_PIPELINE_TYPE in ['CONFIGURE_DEV', 'RELEASE'] }
                }
                steps {
                    script {
                        sh 'git config user.email "cib-ci-pipeline@cib.echonet"'
                        sh 'git config user.name "CIB CI Pipeline"'
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Create Release Branch') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'CONFIGURE_DEV' }
                }
                steps {
                    script {
                        def version        = _getMavenVersion(toolVersions)
                        def releaseVersion = version.replace('-SNAPSHOT', '')
                        def branch         = "release/${releaseVersion}"
                        sh "git checkout -b ${branch}"
                        _gitPush(ciConfig, branch)
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Tag Version') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'RELEASE' }
                }
                steps {
                    script {
                        def version = _getMavenVersion(toolVersions)
                        sh "git tag -a v${version} -m 'Release v${version} [ci skip]'"
                        _gitPush(ciConfig, "v${version}")
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Increase Dev Version') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'CONFIGURE_DEV' }
                }
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh '''mvn build-helper:parse-version versions:set \
                                  -DnewVersion=\${parsedVersion.majorVersion}.\${parsedVersion.minorVersion}.\${parsedVersion.nextIncrementalVersion}-SNAPSHOT \
                                  versions:commit --batch-mode'''
                        }
                        sh 'git add pom.xml'
                        sh 'git commit -m "[CI] Bump to next dev SNAPSHOT version [ci skip]"'
                        _gitPush(ciConfig, "HEAD:${env.BRANCH_NAME}")
                    }
                }
            }

            /* ------------------------------------------------------------------ */
            stage('Increase Release Version') {
                when {
                    expression { env.CIB_PIPELINE_TYPE == 'RELEASE' }
                }
                steps {
                    script {
                        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                            sh '''mvn build-helper:parse-version versions:set \
                                  -DnewVersion=\${parsedVersion.majorVersion}.\${parsedVersion.nextMinorVersion}.0-SNAPSHOT \
                                  versions:commit --batch-mode'''
                        }
                        sh 'git add pom.xml'
                        sh 'git commit -m "[CI] Bump to next release SNAPSHOT version [ci skip]"'
                        _gitPush(ciConfig, "HEAD:${env.BRANCH_NAME}")
                    }
                }
            }
        }

        post {
            always {
                junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
                cleanWs()
            }
            success {
                echo "Pipeline succeeded — build #${env.BUILD_NUMBER}"
            }
            failure {
                echo "Pipeline failed — build #${env.BUILD_NUMBER}"
            }
        }
    }
}

/* ────────────────────────────── helpers ────────────────────────────────── */

private String _getMavenVersion(Map toolVersions) {
    def version = ''
    withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
        version = sh(returnStdout: true,
            script: 'mvn help:evaluate -Dexpression=project.version -q -DforceStdout').trim()
    }
    return version
}

private void _deployToArtifactory(Map ciConfig, Map toolVersions, String repo) {
    def creds = ciConfig.secrets?.artifactory
    if (creds?.jenkins?.name) {
        withCredentials([usernamePassword(
            credentialsId: creds.jenkins.name,
            usernameVariable: 'ART_USER',
            passwordVariable: 'ART_PASS'
        )]) {
            withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
                sh "mvn deploy --batch-mode -DskipTests -Dartifactory.repo=${repo}"
            }
        }
    } else {
        // CyberArk — credentials resolved by the CyberArk Jenkins plugin
        withMaven(maven: toolVersions.maven, jdk: toolVersions.java) {
            sh "mvn deploy --batch-mode -DskipTests -Dartifactory.repo=${repo}"
        }
    }
}

private void _buildAndPushDocker(Map ciConfig, String tag) {
    def registry = ciConfig.docker?.registry ?: 'registry.cib.echonet'
    def image    = "${registry}/${env.JOB_BASE_NAME}:${tag}"
    sh "docker build -t ${image} ."
    sh "docker push ${image}"
}

private void _gitPush(Map ciConfig, String ref) {
    def creds = ciConfig.secrets?.git_ssh
    if (creds?.jenkins?.name) {
        withCredentials([sshUserPrivateKey(
            credentialsId: creds.jenkins.name,
            keyFileVariable: 'GIT_SSH_KEY',
            passphraseVariable: ''
        )]) {
            sh "GIT_SSH_COMMAND='ssh -i \$GIT_SSH_KEY -o StrictHostKeyChecking=no' git push origin ${ref}"
        }
    } else {
        sh "git push origin ${ref}"
    }
}
