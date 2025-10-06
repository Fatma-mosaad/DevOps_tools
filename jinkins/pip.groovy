def currentStage = ""
pipeline {
   agent { label 'vault' }

    environment {
        PATH = "/root/bin:${env.PATH}"
        KUBECONFIG        = "${HOME}/.kube/config"
        CLUSTER_NAME      = "ci-test"
        VAULT_NAMESPACE   = "vault"
        VAULT_REVIEWER_SA = "vault-auth"
        APP_SA_NAME       = "myapp-sa"
        POLICY_NAME       = "myapp"
    }

    options {
        timestamps()
    }

    stages {
        
        stage('Check & Install Required Tools') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        sh '''
                         set -e

                        check_install() {
                            TOOL=$1
                            INSTALL_CMD=$2
                            if ! command -v "$TOOL" >/dev/null 2>&1; then
                                echo "==> $TOOL not found ❌ Installing..."
                                eval "$INSTALL_CMD"
                                if command -v "$TOOL" >/dev/null 2>&1; then
                                    echo "==> $TOOL installed successfully ✅"
                                else
                                    echo "==> Failed to install $TOOL ❌"
                                    exit 1
                                fi
                            else
                                echo "==> $TOOL already installed ✅"
                            fi
                        }
        
                        mkdir -p $HOME/bin
        
                        check_install kind "curl -Lo $HOME/bin/kind https://kind.sigs.k8s.io/dl/v0.23.0/kind-linux-amd64 && chmod +x $HOME/bin/kind"
                        check_install kubectl "curl -sSL -o $HOME/bin/kubectl https://dl.k8s.io/release/$(curl -sSL https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl && chmod +x $HOME/bin/kubectl"
                        check_install helm "curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash"
                        check_install jq "apt-get update && apt-get install -y jq || yum install -y jq"
                        check_install curl "apt-get update && apt-get install -y curl || yum install -y curl"
                        check_install base64 "apt-get update && apt-get install -y coreutils || yum install -y coreutils"
                        '''
                    }
                }
            }
        }
        
        
        stage('Create KinD Cluster') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
        
                        def exists = sh(
                            script: "kind get clusters | grep -q \"^${CLUSTER_NAME}\$\" && echo true || echo false",
                            returnStdout: true
                        ).trim()
        
                        if (exists == "true") {
                            echo "==> Cluster ${CLUSTER_NAME} already exists, skipping ✅"
                        } else {
                            echo "==> Creating KinD cluster ${CLUSTER_NAME} 🕹️"
        
                            sh """
                            set -eux
                            kind delete cluster --name ${CLUSTER_NAME} || true
        
                            # Create KinD cluster
                            kind create cluster --name ${CLUSTER_NAME} --wait 180s
        
                            mkdir -p ${WORKSPACE}/.kube
                            kind get kubeconfig --name ${CLUSTER_NAME} > ${WORKSPACE}/.kube/config
                            chmod 600 ${WORKSPACE}/.kube/config
        
                            # Verify cluster is responsive
                            KUBECONFIG=${WORKSPACE}/.kube/config kubectl get nodes -o wide
                            """
        
                            // Update Jenkins environment so all next stages see correct cluster
                            env.KUBECONFIG = "${WORKSPACE}/.kube/config"
                            echo "==> Cluster ${CLUSTER_NAME} created and kubeconfig exported ✅"
                        }
                    }
                }
            }
        }


        stage('Check Cluster Nodes') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(40) {
                            sh '''
                            echo "==> Checking nodes..."
                            NOT_READY=$(kubectl get nodes --no-headers | awk '$2 != "Ready" {print $1}')
                            if [ -n "$NOT_READY" ]; then
                                echo "Nodes not Ready yet ❌"
                                exit 1
                            fi
                            echo "Nodes are Ready ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Install Vault (HA Mode)') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(3) {
                            sh '''
                            if helm status vault -n vault >/dev/null 2>&1; then
                                echo "Vault already installed ✅"
                                exit 0
                            fi
                            helm repo add hashicorp https://helm.releases.hashicorp.com
                            helm repo update
                            helm install vault hashicorp/vault --namespace vault --create-namespace \
                              --set server.ha.enabled=true \
                              --set server.standalone.enabled=false \
                              --set server.dataStorage.enabled=true \
                              --set server.dataStorage.size=6Gi \
                              --set server.ha.raft.enabled=true \
                              --set server.ha.replicas=1 \
                              --set ui.enabled=true \
                              --set injector.enabled=true
                            '''
                            echo "==> Vault installed successfully in HA mode ✅"
                        }
                    }
                }
            }
        }

        stage('Wait Vault to be Ready') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(60) {
                            sh '''
                            STATUS=$(kubectl get pod vault-0 -n vault -o jsonpath='{.status.phase}' || echo "Pending")
                            if [ "$STATUS" != "Running" ]; then
                                echo "Vault pod not Ready yet ❌"
                                sleep 10
                                exit 1
                            fi
                            echo "Vault pod Running ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Initialize Vault (HA)') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(3) {
                            sh '''
                            INIT=$(kubectl exec -n vault vault-0 -- vault status -format=json | jq -r .initialized || echo false)
                            if [ "$INIT" = "true" ]; then
                                echo "Vault already initialized ✅"
                                exit 0
                            fi
                            kubectl exec -n vault vault-0 -- \
                              vault operator init -key-shares=3 -key-threshold=2 \
                              -format=json > vault_init.json
                            echo "Vault initialized ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Unseal Vault (HA)') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        def sealed = sh(
                            script: "kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- vault status -format=json | jq -r .sealed",
                            returnStdout: true
                        ).trim()

                        if (sealed == "false") {
                            echo "==> Vault already unsealed, skipping ✅"
                        } else {
                            sh '''
                            KEYS=$(jq -r '.unseal_keys_b64[0:2][]' vault_init.json)
                            for key in $KEYS; do
                              kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault operator unseal $key
                            done
                            echo "==> Vault unsealed successfully ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Login Root Token') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        def loggedIn = sh(
                            script: "kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault token lookup -format=json | jq -r .data.id || echo ''",
                            returnStdout: true
                        ).trim()

                        if (loggedIn?.trim()) {
                            echo "==> Already logged in with root token inside vault-0, skipping ✅"
                        } else {
                            sh '''
                            ROOT_TOKEN=$(jq -r .root_token vault_init.json)
                            if kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault login $ROOT_TOKEN; then
                              echo "==> Logged in with root token successfully ✅"
                            else
                              echo "Login failed but ignoring (idempotent) ⚠️"
                            fi
                            '''
                        }
                    }
                }
            }
        }

        stage('Enable KV Secret Engine') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        def exists = sh(
                            script: "kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault secrets list -format=json | jq -r 'has(\"secret/\")'",
                            returnStdout: true
                        ).trim()

                        if (exists == "true") {
                            echo "==> KV secrets engine already enabled, skipping ✅"
                        } else {
                            sh '''
                            kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault secrets enable -path=secret kv-v2
                            echo "==> KV secrets engine enabled successfully ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Config K8s Auth Vault') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(3) {
                            sh '''
                            echo "==> Configuring Kubernetes Auth in Vault"

                            if kubectl get sa ${VAULT_REVIEWER_SA} -n ${VAULT_NAMESPACE} >/dev/null 2>&1; then
                              echo "ServiceAccount ${VAULT_REVIEWER_SA} already exists ✅"
                            else
                              kubectl create sa ${VAULT_REVIEWER_SA} -n ${VAULT_NAMESPACE}
                              echo "ServiceAccount ${VAULT_REVIEWER_SA} created ✅"
                            fi

                            if kubectl get clusterrolebinding ${VAULT_REVIEWER_SA}-auth-delegator >/dev/null 2>&1; then
                              echo "ClusterRoleBinding already exists ✅"
                            else
                              kubectl create clusterrolebinding ${VAULT_REVIEWER_SA}-auth-delegator \
                                --clusterrole=system:auth-delegator \
                                --serviceaccount=${VAULT_NAMESPACE}:${VAULT_REVIEWER_SA}
                              echo "ClusterRoleBinding created ✅"
                            fi

                            if kubectl get sa ${APP_SA_NAME} -n ${VAULT_NAMESPACE} >/dev/null 2>&1; then
                              echo "ServiceAccount ${APP_SA_NAME} already exists ✅"
                            else
                              kubectl create sa ${APP_SA_NAME} -n ${VAULT_NAMESPACE}
                              echo "ServiceAccount ${APP_SA_NAME} created ✅"
                            fi

                            if kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault auth enable kubernetes; then
                              echo "Kubernetes auth enabled ✅"
                            else
                              echo "Kubernetes auth already enabled, skipping ✅"
                            fi

                            SA_JWT=$(kubectl create token ${VAULT_REVIEWER_SA} -n ${VAULT_NAMESPACE})
                            KUBE_CA=$(kubectl get configmap -n kube-system kube-root-ca.crt -o jsonpath="{.data['ca\\.crt']}")
                            KUBE_CA_B64=$(echo "${KUBE_CA}" | base64 | tr -d '\n')
                            KUBE_HOST="https://kubernetes.default.svc:443"

                            kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/sh -c "echo ${KUBE_CA_B64} | base64 -d > /tmp/ca.crt && \
                                /bin/vault write auth/kubernetes/config \
                                  token_reviewer_jwt='${SA_JWT}' \
                                  kubernetes_host='${KUBE_HOST}' \
                                  kubernetes_ca_cert=@/tmp/ca.crt \
                                  issuer='https://kubernetes.default.svc.cluster.local' || true"

                            echo "==> Kubernetes auth configured in Vault ✅"
                            '''
                        }
                    }
                }
            }
        }

        stage('Create Vault Policy') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        sh '''
                        echo "==> Creating Vault policy: ${POLICY_NAME}"
                        cat <<EOF | kubectl exec -i -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault policy write ${POLICY_NAME} -
path "secret/data/myapp/*" {
  capabilities = ["read"]
}


EOF
                        '''
                    }
                }
            }
        }

        stage('Create Vault Role') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        sh '''
                        echo "==> Creating Vault role for ServiceAccount ${APP_SA_NAME} and attaching policy ${POLICY_NAME}"
                        kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault write auth/kubernetes/role/${POLICY_NAME} \
                              bound_service_account_names=${APP_SA_NAME} \
                              bound_service_account_namespaces=${VAULT_NAMESPACE} \
                              policies=${POLICY_NAME} \
                              ttl=24h \
                              audience="https://kubernetes.default.svc.cluster.local"
                        '''
                    }
                }
            }
        }

        stage('Write Vault Secret') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        sh '''

                         if [ -f /db.json  ]; then
                          echo "==> Found db.json, uploading directly into Vault"
                            kubectl exec -i -n ${VAULT_NAMESPACE} vault-0 -- \
                                vault kv put secret/myapp/db - < /db.json  
                            echo "==> Secret written successfully ✅"
                        else
                            echo "db.json missing ❌"
                            exit 1
                        fi
                        '''
                    }
                }
            }
        }

        stage('Deploy App Pod') {
            steps {
                script {
                    currentStage = env.STAGE_NAME
                    try {
                        sh """
                        echo "==> Deploying Pod with Vault Agent Injector to fetch secret (pod runs in ${VAULT_NAMESPACE})"

                        kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: myapp
  namespace: ${VAULT_NAMESPACE}
  labels:
    app: myapp
  annotations:
    vault.hashicorp.com/agent-inject: "true"
    vault.hashicorp.com/role: ${POLICY_NAME}
    vault.hashicorp.com/agent-inject-secret-db.txt: "secret/data/myapp/db"
spec:
  serviceAccountName: ${APP_SA_NAME}
  containers:
  - name: myapp
    image: busybox
    command: ["/bin/sh"]
    args: ["-c", "sleep 3600"]
EOF

                        echo "✅ App pod deployed successfully"
                        """
                    } catch (err) {
                        echo "❌ Failed to deploy app pod"
                        def logSnippet = sh(
                            script: 'kubectl describe pod myapp -n ${VAULT_NAMESPACE}',
                            returnStdout: true
                        ).trim()
                        slackSend(
                            channel: '#jenkins-alerts',
                            color: 'danger',
                            message: "*Stage: Deploy App Pod FAILED*\nError: ${err}\nLogs:\n``` ${logSnippet.take(1000)} ```"
                        )
                        error("Stage failed: Deploy App Pod")
                    }
                }
            }
        }

        stage('Verify Secret Injection') {
            steps {
                ansiColor('xterm') {
                    script {
                        currentStage = env.STAGE_NAME
                        retry(3) {
                            script {
                                echo "==> Waiting for pod 'myapp' init containers to finish..."
                                sh '''
                                for i in {1..30}; do
                                  INIT_READY=$(kubectl get pod myapp -n ${VAULT_NAMESPACE} -o jsonpath='{.status.initContainerStatuses[*].ready}' 2>/dev/null || echo "false")
                                  if [ "$INIT_READY" = "true" ]; then
                                    echo "==> Init containers finished ✅"
                                    break
                                  fi
                                  echo "==> Init containers not ready yet, retrying in 10s..."
                                  sleep 10
                                done
                                '''

                                echo "==> Waiting for main containers to be Ready..."
                                sh '''
                                for i in {1..30}; do
                                  STATUS=$(kubectl get pod myapp -n ${VAULT_NAMESPACE} -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")
                                  READY=$(kubectl get pod myapp -n ${VAULT_NAMESPACE} -o jsonpath='{.status.containerStatuses[*].ready}' 2>/dev/null || echo "false")
                                  if [ "$STATUS" = "Running" ] && echo "$READY" | grep -q "true"; then
                                    echo "==> Pod is Running and Ready ✅"
                                    break
                                  fi
                                  echo "==> Pod not ready yet (STATUS=$STATUS, READY=$READY), retrying in 10s..."
                                  sleep 10
                                done
                                '''

                                echo "==> Getting container names inside myapp pod..."
                                def containers = sh(
                                    script: "kubectl get pod myapp -n ${VAULT_NAMESPACE} -o jsonpath='{.spec.containers[*].name}'",
                                    returnStdout: true
                                ).trim()
                                echo "Containers found: ${containers}"

                                containers.split(" ").each { c ->
                                    echo "==> Checking container: ${c}"
                                    sh """
                                    kubectl exec -n ${VAULT_NAMESPACE} myapp -c ${c} -- sh -c 'ls -l /vault/secrets/ '
                                    """
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            slackSend(channel: 'vault-centralized-resources',
                      color: 'good',
                      message: "✅ Pipeline finished successfully: ${env.JOB_NAME} #${env.BUILD_NUMBER}")
        }
        failure {
            slackSend(channel: 'vault-centralized-resources',
                      color: 'danger',
                      message: "❌ Pipeline failed in stage: *${currentStage}* (Job: ${env.JOB_NAME} #${env.BUILD_NUMBER})")
        }
        always {
            echo "ℹ️ Pipeline completed (success or failure)."
        }
    }
}

