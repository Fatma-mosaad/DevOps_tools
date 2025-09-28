pipeline {
    agent any

    environment {
        KUBECONFIG = "${HOME}/.kube/config"
        CLUSTER_NAME    = "ci-test"
        VAULT_NAMESPACE = "vault"
        VAULT_REVIEWER_SA = "vault-auth"   // used by Vault to review tokens
        APP_SA_NAME     = "myapp-sa"       // used by the application pod
        POLICY_NAME     = "myapp"
    }

    stages {

        stage('Create KinD Cluster') {
            steps {
                script {
                    def exists = sh(
                        script: "kind get clusters | grep -q \"^${CLUSTER_NAME}\$\" && echo true || echo false",
                        returnStdout: true
                    ).trim()
        
                    if (exists == "true") {
                        echo "==> Cluster ${CLUSTER_NAME} already exists, skipping ✅"
                    } else {
                        sh """
                        echo "==> Creating KinD cluster: ${CLUSTER_NAME}"
                        kind create cluster --name ${CLUSTER_NAME} --wait 120s
        
                        echo "==> Exporting kubeconfig for cluster: ${CLUSTER_NAME}"
                        mkdir -p ~/.kube
                        kind get kubeconfig --name ${CLUSTER_NAME} > ~/.kube/config
                        chmod 600 ~/.kube/config
        
                        echo "==> Current kubectl context:"
                        kubectl config current-context
                        """
                        echo "==> Cluster ${CLUSTER_NAME} created successfully ✅"
                    }
                }
            }
        }


        stage('Check Cluster Nodes') {
            steps {
                sh '''
                echo "==> Checking if cluster nodes are Ready..."
                for i in {1..10}; do
                  NOT_READY=$(kubectl get nodes --no-headers 2>/dev/null | awk '$2 != "Ready" {print $1}' || true)
                  if [ -z "$NOT_READY" ]; then
                    echo "==> All nodes are Ready ✅"
                    break
                  fi
                  echo "==> Nodes not Ready yet, retrying in 10s... (attempt $i/10)"
                  sleep 10
                done

                echo "==> Cluster Nodes:"
                kubectl get nodes -o wide
                echo "==> Exporting kubeconfig for cluster: ${CLUSTER_NAME}"
                mkdir -p ~/.kube
                kind get kubeconfig --name ${CLUSTER_NAME} > ~/.kube/config
                chmod 600 ~/.kube/config
                echo "==> Current kubectl context:"
                kubectl config current-context
                '''
            }
        }

        stage('Install Helm') {
            steps {
                sh '''
                if ! command -v helm >/dev/null 2>&1; then
                  curl -fsSL https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
                fi
                helm version
                echo "==> Helm installed successfully ✅"
                '''
            }
        }

        stage('Install Vault (HA Mode)') {
            steps {
                script {
                    def installed = sh(
                        script: "kubectl get pods -n ${VAULT_NAMESPACE} -l app.kubernetes.io/name=vault --no-headers 2>/dev/null | wc -l",
                        returnStdout: true
                    ).trim()

                    if (installed.toInteger() > 0) {
                        echo "==> Vault already installed in namespace ${VAULT_NAMESPACE}, skipping ✅"
                    } else {
                        sh """
                        helm repo add hashicorp https://helm.releases.hashicorp.com
                        helm repo update
                        helm upgrade --install vault hashicorp/vault \
                          --namespace vault --create-namespace \
                          --set server.ha.enabled=true \
                          --set server.standalone.enabled=false \
                          --set server.dataStorage.enabled=true \
                          --set server.dataStorage.size=6Gi \
                          --set server.ha.raft.enabled=true \
                          --set server.ha.replicas=1 \
                          --set ui.enabled=true \
                          --set injector.enabled=true


                        """
                        echo "==> Vault installed successfully in HA mode ✅"
                    }
                }
            }
        }
        stage('Wait for Vault to be Ready') {
            steps {
                sh """
                echo "==> Waiting for Vault container inside vault-0 to be ready..."
                for i in {1..30}; do
                  STATUS=\$(kubectl get pod vault-0 -n ${VAULT_NAMESPACE} -o jsonpath='{.status.phase}' 2>/dev/null || echo "Pending")
                  READY=\$(kubectl get pod vault-0 -n ${VAULT_NAMESPACE} -o jsonpath='{.status.containerStatuses[0].ready}' 2>/dev/null || echo "false")

                  if [ "\$STATUS" = "Running" ] && [ "\$READY" = "true" ]; then
                    echo "==> Vault pod vault-0 is Running and Ready ✅"
                    break
                  fi

                  echo "==> Vault pod not ready yet (STATUS=\$STATUS, READY=\$READY), retrying in 10s..."
                  sleep 10
                done

                echo "==> Final pod status:"
                kubectl get pods -n ${VAULT_NAMESPACE} -o wide
                """
            }
        }

                


        stage('Install jq') {
            steps {
                sh '''
                if ! command -v jq >/dev/null 2>&1; then
                  apt-get update && apt-get install -y jq
                fi
                echo "==> jq installed successfully ✅"
                '''
            }
        }

        stage('Initialize Vault (HA)') {
            steps {
                script {
                    def status = sh(
                        script: "kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- vault status -format=json | jq -r .initialized || echo false",
                        returnStdout: true
                    ).trim()

                    if (status == "false") {
                        sh """
                        kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- \
                          vault operator init -key-shares=3 -key-threshold=2 \
                          -format=json > vault_init.json
                        """
                        echo "==> Vault initialized successfully ✅"
                    } else {
                        echo "==> Vault already initialized, skipping ✅"
                    }
                }
            }
        }

        stage('Unseal Vault (HA)') {
            steps {
                script {
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

        stage('Login Root Token') {
            steps {
                script {
                    // If you already have VAULT_TOKEN in the pod env or mounted, you can skip this; we keep idempotent approach
                    def loggedIn = sh(
                        script: "kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault token lookup -format=json | jq -r .data.id || echo ''",
                        returnStdout: true
                    ).trim()

                    if (loggedIn) {
                        echo "==> Already logged in with root token inside vault-0, skipping ✅"
                    } else {
                        sh '''
                        ROOT_TOKEN=$(jq -r .root_token vault_init.json)
                        kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault login $ROOT_TOKEN || true
                        echo "==> Logged in with root token successfully ✅"
                        '''
                    }
                }
            }
        }

        stage('Enable KV Secret Engine') {
            steps {
                script {
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

        stage('Configure Kubernetes Auth in Vault') {
            steps {
                sh '''
                echo "==> Configuring Kubernetes Auth in Vault"

                # Ensure reviewer ServiceAccount exists (used by Vault to review tokens)
                kubectl create sa ${VAULT_REVIEWER_SA} -n ${VAULT_NAMESPACE} || true

                # Grant system:auth-delegator to the reviewer SA so Vault can call TokenReview API
                kubectl create clusterrolebinding ${VAULT_REVIEWER_SA}-auth-delegator \
                  --clusterrole=system:auth-delegator \
                  --serviceaccount=${VAULT_NAMESPACE}:${VAULT_REVIEWER_SA} || true

                # Ensure an app ServiceAccount exists (for the application pod)
                kubectl create sa ${APP_SA_NAME} -n ${VAULT_NAMESPACE} || true

                # Enable Kubernetes auth in Vault (ignore error if already enabled)
                kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault auth enable kubernetes || true

                # Retrieve tokens and CA (locally)
                SA_JWT=$(kubectl create token ${VAULT_REVIEWER_SA} -n ${VAULT_NAMESPACE})
                KUBE_CA=$(kubectl get configmap -n kube-system kube-root-ca.crt -o jsonpath="{.data['ca\\.crt']}")

                # base64 encode the CA to safely transfer into the pod (avoids newline issues)
                KUBE_CA_B64=$(echo "${KUBE_CA}" | base64 | tr -d '\n')

                # Kubernetes API server inside cluster
                KUBE_HOST="https://kubernetes.default.svc:443"

                # Put the CA into a file inside the vault-0 pod and configure kubernetes auth
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

        stage('Create Vault Policy') {
            steps {
                sh '''
                echo "==> Creating Vault policy: ${POLICY_NAME}"
                cat <<EOF | kubectl exec -i -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault policy write ${POLICY_NAME} -
path "secret/data/myapp/*" {
  capabilities = ["read"]
}

path "secret/metadata/myapp/*" {
  capabilities = ["read"]
}
EOF
                '''
            }
        }

        stage('Create Vault Role') {
            steps {
                sh '''
                echo "==> Creating Vault role for ServiceAccount ${APP_SA_NAME} and attaching policy ${POLICY_NAME}"
                # Include audience to match typical K8s service account issuer to avoid aud mismatch
                kubectl exec -n ${VAULT_NAMESPACE} vault-0 -- /bin/vault write auth/kubernetes/role/${POLICY_NAME} \
                  bound_service_account_names=${APP_SA_NAME} \
                  bound_service_account_namespaces=${VAULT_NAMESPACE} \
                  policies=${POLICY_NAME} \
                  ttl=24h \
                  audience="https://kubernetes.default.svc.cluster.local"
                '''
            }
        }
        stage('Write Vault Secret') {
            steps {
                sh '''
                echo "==> Writing secret into Vault from file"
        
                if [ -f /var/lib/jenkins/workspace/pip/db.json ]; then
                  echo "==> Found db.json, uploading directly into Vault"
                  kubectl exec -i -n ${VAULT_NAMESPACE} vault-0 -- \
                    vault kv put secret/myapp/db - < /var/lib/jenkins/workspace/pip/db.json
        
                  echo "==> Secret written successfully ✅"
                else
                  echo "⚠️  No db.json file found, skipping secret upload"
                fi
                '''
            }
        }


       



        stage('Deploy App Pod') {
            steps {
                sh '''
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
    # Vault role must match the Vault role we created (POLICY_NAME)
    vault.hashicorp.com/role: ${POLICY_NAME}
    # For a KV v2 mount use the "data" path
    vault.hashicorp.com/agent-inject-secret-db.txt: "secret/data/myapp/db"
spec:
  serviceAccountName: ${APP_SA_NAME}
  containers:
  - name: myapp
    image: busybox
    command: ["/bin/sh"]
    args: ["-c", "sleep 3600"]
EOF
                '''
            }
        }

        stage('Verify Vault Secret Injection') {
            steps {
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
                        kubectl exec -n ${VAULT_NAMESPACE} myapp -c ${c} -- sh -c 'ls -l /vault/secrets/ || true'
                        """
                    }
                }
            }
        }

    } // stages
} // pipeline
