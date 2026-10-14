
pipeline {
    agent any

    parameters {
        choice(
            name: 'APPLICATION',
            choices: ['payment-app', 'customer-portal-api', 'order-app'],
            description: 'Select the application to deploy'
        )

        string(
            name: 'IMAGE_TAG',
            defaultValue: 'latest',
            description: 'Image tag in the private registry'
        )

        string(
            name: 'CONTAINER_PORT',
            defaultValue: '',
            description: 'Container port; leave blank to use the application default'
        )

        string(
            name: 'SERVICE_PORT',
            defaultValue: '',
            description: 'Kubernetes Service port; leave blank to use the application default'
        )

        string(
            name: 'REPLICAS',
            defaultValue: '1',
            description: 'Number of application replicas'
        )

        choice(
            name: 'EXPOSE_SERVICE',
            choices: ['default', 'true', 'false'],
            description: 'default uses the application default; true creates a LoadBalancer; false creates ClusterIP'
        )
    }

    environment {
        DO_TOKEN = credentials('do-token')
        CLUSTER = 'payment-app-cluster'
        REGISTRY = '104.248.121.80:5000'
    }

    stages {
        stage('Configure application') {
            steps {
                script {
                    def appConfig = [
                        'payment-app': [
                            imageName: 'payment-app',
                            namespace: 'payment-app',
                            serviceName: 'payment-app-service',
                            containerPort: '8080',
                            servicePort: '80',
                            expose: 'true'
                        ],
                        'customer-portal-api': [
                            imageName: 'customer-portal-api',
                            namespace: 'customer-portal-api',
                            serviceName: 'customer-portal-api-service',
                            containerPort: '6060',
                            servicePort: '6060',
                            expose: 'false'
                        ],
                        'order-app': [
                            imageName: 'order-app',
                            namespace: 'order-app',
                            serviceName: 'order-app-service',
                            containerPort: '8080',
                            servicePort: '80',
                            expose: 'true'
                        ]
                    ]

                    def cfg = appConfig[params.APPLICATION]

                    env.APP_NAME = params.APPLICATION
                    env.NAMESPACE = cfg.namespace
                    env.SERVICE_NAME = cfg.serviceName
                    env.DOCKER_IMG = "${env.REGISTRY}/${cfg.imageName}:${params.IMAGE_TAG}"
                    env.CONTAINER_PORT = params.CONTAINER_PORT?.trim()
                        ? params.CONTAINER_PORT.trim() : cfg.containerPort
                    env.SERVICE_PORT = params.SERVICE_PORT?.trim()
                        ? params.SERVICE_PORT.trim() : cfg.servicePort
                    env.REPLICAS = params.REPLICAS.trim()
                    env.EXPOSE_SERVICE = params.EXPOSE_SERVICE == 'default'
                        ? cfg.expose : params.EXPOSE_SERVICE

                    echo """
                    Application: ${env.APP_NAME}
                    Image: ${env.DOCKER_IMG}
                    Namespace: ${env.NAMESPACE}
                    Container port: ${env.CONTAINER_PORT}
                    Service port: ${env.SERVICE_PORT}
                    Replicas: ${env.REPLICAS}
                    Public LoadBalancer: ${env.EXPOSE_SERVICE}
                    """
                }
            }
        }

        stage('Validate configuration') {
            steps {
                sh '''
                    set -eu

                    printf '%s' "$NAMESPACE" |
                      grep -Eq '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'

                    printf '%s' "$APP_NAME" |
                      grep -Eq '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'

                    printf '%s' "$SERVICE_NAME" |
                      grep -Eq '^[a-z0-9]([-a-z0-9]*[a-z0-9])?$'

                    printf '%s' "$CONTAINER_PORT" | grep -Eq '^[0-9]+$'
                    printf '%s' "$SERVICE_PORT" | grep -Eq '^[0-9]+$'
                    printf '%s' "$REPLICAS" | grep -Eq '^[1-9][0-9]*$'

                    test "$CONTAINER_PORT" -ge 1
                    test "$CONTAINER_PORT" -le 65535
                    test "$SERVICE_PORT" -ge 1
                    test "$SERVICE_PORT" -le 65535
                    test -n "$DOCKER_IMG"

                    case "$EXPOSE_SERVICE" in
                      true|false) ;;
                      *) echo "Invalid EXPOSE_SERVICE value"; exit 1 ;;
                    esac

                    echo "Configuration validated."
                '''
            }
        }

        stage('Connect to DigitalOcean Kubernetes') {
            steps {
                sh '''
                    set -eu

                    mkdir -p "$WORKSPACE/bin" "$WORKSPACE/.kube"

                    curl -fsSL \
                      "https://github.com/digitalocean/doctl/releases/download/v1.117.0/doctl-1.117.0-linux-amd64.tar.gz" \
                      -o "$WORKSPACE/doctl.tar.gz"

                    tar -xzf "$WORKSPACE/doctl.tar.gz" \
                      -C "$WORKSPACE/bin"

                    chmod 700 "$WORKSPACE/bin/doctl"

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    doctl version
                    doctl auth init --access-token "$DO_TOKEN"
                    doctl kubernetes cluster kubeconfig save "$CLUSTER"

                    echo "===== Kubernetes context ====="
                    kubectl config current-context

                    echo "===== Kubernetes nodes ====="
                    kubectl get nodes
                '''
            }
        }

        stage('Deploy application') {
            steps {
                sh '''
                    set -eu

                    export PATH="$WORKSPACE/bin:$PATH"
                    export KUBECONFIG="$WORKSPACE/.kube/config"

                    kubectl create namespace "$NAMESPACE" \
                      --dry-run=client -o yaml | kubectl apply -f -

                    cat > "$WORKSPACE/deployment.yaml" <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${APP_NAME}
  namespace: ${NAMESPACE}
spec:
  replicas: ${REPLICAS}
  selector:
    matchLabels:
      app: ${APP_NAME}
  template:
    metadata:
      labels:
        app: ${APP_NAME}
    spec:
      containers:
        - name: ${APP_NAME}
          image: ${DOCKER_IMG}
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: ${CONTAINER_PORT}
              protocol: TCP
EOF

                    echo "===== Applying Deployment ====="
                    kubectl apply -f "$WORKSPACE/deployment.yaml"

                    echo "===== Restarting Deployment ====="
                    kubectl rollout restart "deployment/$APP_NAME" \
                      -n "$NAMESPACE"

                    echo "===== Waiting for rollout ====="
                    kubectl rollout status "deployment/$APP_NAME" \
                      -n "$NAMESPACE" --timeout=180s

                    cat > "$WORKSPACE/service.yaml" <<EOF
apiVersion: v1
kind: Service
metadata:
  name: ${SERVICE_NAME}
  namespace: ${NAMESPACE}
spec:
  type: $(if [ "$EXPOSE_SERVICE" = "true" ]; then echo LoadBalancer; else echo ClusterIP; fi)
  selector:
    app: ${APP_NAME}
  ports:
    - name: http
      port: ${SERVICE_PORT}
      targetPort: ${CONTAINER_PORT}
      protocol: TCP
EOF

                    echo "===== Applying Service ====="
                    kubectl apply -f "$WORKSPACE/service.yaml"

                    echo "===== Deployment ====="
                    kubectl get deployment "$APP_NAME" -n "$NAMESPACE"

                    echo "===== Pods ====="
                    kubectl get pods -n "$NAMESPACE" -o wide

                    echo "===== Deployed image ====="
                    kubectl get deployment "$APP_NAME" \
                      -n "$NAMESPACE" \
                      -o jsonpath='{.spec.template.spec.containers[0].image}{"\\n"}'

                    echo "===== Service ====="
                    kubectl get service "$SERVICE_NAME" -n "$NAMESPACE"

                    echo "===== Service endpoints ====="
                    kubectl get endpoints "$SERVICE_NAME" -n "$NAMESPACE"

                    if [ "$EXPOSE_SERVICE" = "true" ]; then
                      echo "===== LoadBalancer external IP ====="
                      kubectl get service "$SERVICE_NAME" \
                        -n "$NAMESPACE" \
                        -o jsonpath='{.status.loadBalancer.ingress[0].ip}{"\\n"}'
                    else
                      echo "===== Internal service URL ====="
                      echo "http://${SERVICE_NAME}.${NAMESPACE}.svc.cluster.local:${SERVICE_PORT}"
                    fi

                    echo "Deployment completed successfully."
                '''
            }
        }
    }

    post {
        success {
            echo 'Common deployment completed successfully.'
        }

        failure {
            echo 'Deployment failed. Check the Jenkins Console Output.'
        }

        always {
            sh '''
                rm -rf "$WORKSPACE/.kube" "$WORKSPACE/bin" || true
                rm -f "$WORKSPACE/deployment.yaml" \
                      "$WORKSPACE/service.yaml" \
                      "$WORKSPACE/doctl.tar.gz" || true
            '''

            echo 'Common deployment pipeline finished.'
        }
    }
}
