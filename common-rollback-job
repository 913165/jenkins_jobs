
pipeline {
    agent any

    parameters {
        choice(
            name: 'CLUSTER',
            choices: ['order-app', 'payment-app-cluster'],
            description: 'Select DigitalOcean Kubernetes cluster'
        )

        choice(
            name: 'APP_NAME',
            choices: ['payment-app', 'order-app'],
            description: 'Application to roll back'
        )

        string(
            name: 'IMAGE_VERSION',
            defaultValue: '1.0.4',
            description: 'Existing image version to restore'
        )

        string(
            name: 'NAMESPACE',
            defaultValue: 'payment-app',
            description: 'Kubernetes namespace containing the Deployment'
        )
    }

    environment {
        REGISTRY = '104.248.121.80:5000'
        DO_TOKEN = credentials('do-token')
    }

    stages {
        stage('Validate Parameters') {
            steps {
                script {
                    def versionParts = params.IMAGE_VERSION.split('\\.', -1)

                    if (versionParts.length != 3 ||
                        versionParts.any { part ->
                            part.length() == 0 ||
                            !part.every { ch -> ch >= '0' && ch <= '9' }
                        }) {
                        error('Invalid version. Use format x.y.z, e.g. 1.0.4')
                    }

                    if (!(params.APP_NAME in ['payment-app', 'order-app'])) {
                        error('Unsupported application.')
                    }

                    if (!(params.CLUSTER in ['order-app', 'payment-app-cluster'])) {
                        error('Unsupported cluster.')
                    }

                    if (params.NAMESPACE.trim().isEmpty()) {
                        error('Namespace cannot be empty.')
                    }

                    def expectedCluster = [
                        'payment-app': 'payment-app-cluster',
                        'order-app'  : 'order-app'
                    ]

                    if (expectedCluster[params.APP_NAME] != params.CLUSTER) {
                        error(
                            "Application ${params.APP_NAME} is not mapped to " +
                            "cluster ${params.CLUSTER}."
                        )
                    }
                }
            }
        }

        stage('Configure Cluster Access') {
            steps {
                sh '''
                    set -eu

                    command -v doctl >/dev/null 2>&1 || {
                        echo "ERROR: doctl is not installed."
                        exit 1
                    }

                    command -v kubectl >/dev/null 2>&1 || {
                        echo "ERROR: kubectl is not installed."
                        exit 1
                    }

                    export DIGITALOCEAN_ACCESS_TOKEN="$DO_TOKEN"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    mkdir -p "$WORKSPACE/.kube"
                    chmod 700 "$WORKSPACE/.kube"

                    doctl kubernetes cluster kubeconfig save "$CLUSTER"

                    echo "Selected context:"
                    kubectl config current-context

                    echo "Checking cluster access:"
                    kubectl get nodes
                '''
            }
        }

        stage('Verify Deployment') {
            steps {
                sh '''
                    set -eu
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    echo "Checking Deployment: $APP_NAME"
                    kubectl get deployment "$APP_NAME" -n "$NAMESPACE"

                    echo "Container names:"
                    kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" \
                        -o jsonpath='{.spec.template.spec.containers[*].name}'
                    echo
                '''
            }
        }

        stage('Verify Registry Image') {
            steps {
                sh '''
                    set -eu

                    IMAGE_URL="https://$REGISTRY/v2/$APP_NAME/manifests/$IMAGE_VERSION"

                    echo "Checking image: $REGISTRY/$APP_NAME:$IMAGE_VERSION"

                    HTTP_STATUS=$(curl \
                        --silent \
                        --show-error \
                        --output "$WORKSPACE/registry-manifest.json" \
                        --write-out "%{http_code}" \
                        "$IMAGE_URL" \
                        -H "Accept: application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json")

                    echo "Registry HTTP status: $HTTP_STATUS"

                    if [ "$HTTP_STATUS" != "200" ]; then
                        echo "ERROR: Registry did not confirm the requested image."
                        cat "$WORKSPACE/registry-manifest.json"
                        exit 1
                    fi

                    echo "Registry confirmed the image manifest."
                    rm -f "$WORKSPACE/registry-manifest.json"
                '''
            }
        }

     
        stage('Rollback Application') {
            steps {
                sh '''
                    set -eu
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    IMAGE="$REGISTRY/$APP_NAME:$IMAGE_VERSION"

                    CONTAINER_NAME=$(kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" \
                        -o jsonpath='{.spec.template.spec.containers[0].name}')

                    if [ -z "$CONTAINER_NAME" ]; then
                        echo "ERROR: Could not determine container name."
                        exit 1
                    fi

                    echo "Rolling back to $IMAGE"

                    kubectl set image \
                        "deployment/$APP_NAME" \
                        "$CONTAINER_NAME=$IMAGE" \
                        -n "$NAMESPACE"

                    kubectl rollout status \
                        "deployment/$APP_NAME" \
                        -n "$NAMESPACE" \
                        --timeout=180s

                    echo "Rollback completed."
                '''
            }
        }

        stage('Verify Final State') {
            steps {
                sh '''
                    set -eu
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    kubectl get deployment "$APP_NAME" \
                        -n "$NAMESPACE" -o wide

                    kubectl get pods -n "$NAMESPACE" -o wide
                    kubectl get services -n "$NAMESPACE"
                '''
            }
        }
    }

    post {
        success {
            echo "Rollback successful: ${params.APP_NAME}, cluster ${params.CLUSTER}, version ${params.IMAGE_VERSION}"
        }

        failure {
            echo 'Rollback failed. Check the Jenkins console output.'
        }

        always {
            sh '''
                rm -f "$WORKSPACE/registry-manifest.json"
                rm -rf "$WORKSPACE/.kube"
            '''
        }
    }
}
