
pipeline {
    agent any

    parameters {
        choice(
            name: 'APPLICATION',
            choices: [
                'payment-app',
                'order-app',
                'customer-portal-api'
            ],
            description: 'Select the application to build'
        )

        string(
            name: 'IMAGE_VERSION',
            defaultValue: '1.0.1',
            description: 'Version tag, e.g. 1.0.2'
        )
    }

    environment {
        REGISTRY = '104.248.121.80:5000'
        GIT_BRANCH = 'main'
    }

    stages {
        stage('Configure application') {
            steps {
                script {
                    def appConfig = [
                        'payment-app': [
                            repo: 'https://github.com/913165/paymen_app.git',
                            image: 'payment-app'
                        ],
                        'order-app': [
                            repo: 'https://github.com/913165/order-app.git',
                            image: 'order-app'
                        ],
                        'customer-portal-api': [
                            repo: 'https://github.com/913165/customer-portal-api.git',
                            image: 'customer-portal-api'
                        ]
                    ]

                    def cfg = appConfig[params.APPLICATION]

                    env.GIT_REPO = cfg.repo
                    env.IMAGE_NAME = cfg.image

                    echo "Application: ${params.APPLICATION}"
                    echo "Git repository: ${env.GIT_REPO}"
                    echo "Image: ${env.REGISTRY}/${env.IMAGE_NAME}"
                    echo "Version: ${params.IMAGE_VERSION}"
                }
            }
        }

        stage('Validate configuration') {
            steps {
                sh '''
                    set -eu

                    printf '%s' "$IMAGE_VERSION" |
                      grep -Eq '^[0-9]+\\.[0-9]+\\.[0-9]+$'

                    test -n "$GIT_REPO"
                    test -n "$IMAGE_NAME"
                    test -n "$REGISTRY"

                    echo "Configuration validated."
                '''
            }
        }

        stage('Checkout source code') {
            steps {
                deleteDir()

                git(
                    url: "${GIT_REPO}",
                    branch: "${GIT_BRANCH}",
                    credentialsId: 'github-general-creds'
                )

                sh '''
                    set -eu

                    echo "===== Source revision ====="
                    git log -1 --oneline

                    echo "===== Project files ====="
                    ls -la

                    test -f Dockerfile || {
                        echo "ERROR: Dockerfile not found in repository root."
                        exit 1
                    }
                '''
            }
        }

        stage('Build Docker image') {
            steps {
                sh '''
                    set -eu

                    echo "===== Docker version ====="
                    docker version

                    echo "===== Building image ====="
                    docker build \
                      -t "$REGISTRY/$IMAGE_NAME:$IMAGE_VERSION" \
                      -t "$REGISTRY/$IMAGE_NAME:latest" \
                      .

                    echo "===== Built images ====="
                    docker images "$REGISTRY/$IMAGE_NAME"
                '''
            }
        }

        stage('Login to private registry') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'docker-registry-creds',
                        usernameVariable: 'REGISTRY_USER',
                        passwordVariable: 'REGISTRY_PASSWORD'
                    )
                ]) {
                    sh '''
                        set -eu
                        set +x

                        printf '%s' "$REGISTRY_PASSWORD" |
                          docker login "$REGISTRY" \
                            --username "$REGISTRY_USER" \
                            --password-stdin
                    '''
                }
            }
        }

        stage('Push Docker images') {
            steps {
                sh '''
                    set -eu

                    echo "===== Pushing versioned image ====="
                    docker push "$REGISTRY/$IMAGE_NAME:$IMAGE_VERSION"

                    echo "===== Pushing latest image ====="
                    docker push "$REGISTRY/$IMAGE_NAME:latest"

                    echo "===== Published images ====="
                    echo "$REGISTRY/$IMAGE_NAME:$IMAGE_VERSION"
                    echo "$REGISTRY/$IMAGE_NAME:latest"
                '''
            }
        }

        stage('Verify published image tags') {
            steps {
                sh '''
                    set -eu

                    echo "===== Verifying registry repository ====="

                    curl -kfsS \
                      "https://$REGISTRY/v2/$IMAGE_NAME/tags/list"

                    echo
                    echo "Image push and registry verification completed."
                '''
            }
        }
    }

    post {
        success {
            echo "Build and push completed successfully for ${params.APPLICATION}."
            echo "Version: ${params.IMAGE_VERSION}"
            echo "Image: ${env.REGISTRY}/${env.IMAGE_NAME}:${params.IMAGE_VERSION}"
            echo "Latest: ${env.REGISTRY}/${env.IMAGE_NAME}:latest"
            echo 'You can now run common-deploy-job.'
        }

        failure {
            echo 'Build failed. Check the Jenkins Console Output.'
        }

        always {
            sh '''
                docker logout "$REGISTRY" >/dev/null 2>&1 || true
            '''
        }
    }
}
