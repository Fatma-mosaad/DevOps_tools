pipeline {
    agent any

    triggers {
        // Fallback to polling since no public IP is available for a GitHub webhook yet
        pollSCM('H * * * *')
    }

    environment {
        DEPLOYMENT_STARTED = 'false'
        APP_NAME            = 'users-app'
        DB_NAME             = 'usersdb'
        DB_HOST             = 'postgres'
        DB_PORT             = '5432'
    }

    // No parameters - fully automatic, tag is always BUILD_NUMBER

    stages {

        stage('Checkout') {
            steps {
                git branch: 'main',
                    credentialsId: 'github',
                    url: 'https://github.com/Fatma-mosaad/DevOps_tools.git'
            }
        }

        stage('Build Docker Image') {
            steps {
                sh '''
                    docker build -t ${APP_NAME}:${BUILD_NUMBER} jinkins/user-app
                '''
            }
        }

        stage('Trivy Scan') {
            steps {
                sh '''
                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      aquasec/trivy:latest \
                      image \
                      --severity CRITICAL \
                      --exit-code 1 \
                      ${APP_NAME}:${BUILD_NUMBER}

                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      aquasec/trivy:latest \
                      image \
                      --severity HIGH,MEDIUM,LOW \
                      --exit-code 0 \
                      ${APP_NAME}:${BUILD_NUMBER}
                '''
            }
        }

        stage('Push to Docker Hub') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'docker-hub',
                        usernameVariable: 'DOCKERHUB_USERNAME',
                        passwordVariable: 'DOCKERHUB_TOKEN'
                    )
                ]) {
                    sh '''
                        echo "$DOCKERHUB_TOKEN" | docker login \
                            -u "$DOCKERHUB_USERNAME" \
                            --password-stdin

                        docker tag ${APP_NAME}:${BUILD_NUMBER} \
                            $DOCKERHUB_USERNAME/${APP_NAME}:${BUILD_NUMBER}

                        docker push \
                            $DOCKERHUB_USERNAME/${APP_NAME}:${BUILD_NUMBER}

                        docker logout
                    '''
                }
            }
        }

        

        stage('Check Existing Infrastructure') {
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-key'],
                    sshUserPrivateKey(credentialsId: 'es2-ssh', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')
                ]) {
                    script {
                        def infraReady = sh(
                            returnStatus: true,
                            script: '''
                                cd jinkins/terraform
                                terraform init -input=false >/dev/null 2>&1 || exit 1
                                EC2_IP=$(terraform output -raw instance_public_ip 2>/dev/null) || exit 1
                                [ -n "$EC2_IP" ] || exit 1
                                cd ../..
                                chmod 600 "$SSH_KEY"
                                ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "docker --version" >/dev/null 2>&1
                            '''
                        ) == 0

                        env.SKIP_PROVISION = infraReady ? 'true' : 'false'
                        echo(infraReady
                            ? "EC2 already exists and Docker is ready - skipping Terraform and Ansible, going straight to Deploy."
                            : "No ready infrastructure found - running full Terraform + Ansible provisioning.")
                    }
                }
            }
        }

        stage('Prepare Terraform Variables') {
            steps {
                withCredentials([
                    file(
                        credentialsId: 'terraform-cerd',
                        variable: 'TFVARS_FILE'
                    )
                ]) {
                    sh '''
                        rm -f jinkins/terraform/terraform.tfvars
                        cp "$TFVARS_FILE" jinkins/terraform/terraform.tfvars
                        chmod 600 jinkins/terraform/terraform.tfvars
                    '''
                }
            }
        }

        stage('Terraform Init/Plan/Apply') {
            when { expression { env.SKIP_PROVISION != 'true' } }
            steps {
                withCredentials([
                    [$class: 'AmazonWebServicesCredentialsBinding', credentialsId: 'aws-key']
                ]) {
                    retry(3) {
                        sh '''
                            cd jinkins/terraform
                            terraform init
                            terraform plan
                            terraform apply -auto-approve
                        '''
                    }
                }
            }
        }

        stage('Wait for SSH Ready') {
            when { expression { env.SKIP_PROVISION != 'true' } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'es2-ssh', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                    sh '''
                        chmod 600 "$SSH_KEY"
                        EC2_IP=$(cd jinkins/terraform && terraform output -raw instance_public_ip)
                        MAX_ATTEMPTS=15
                        ATTEMPT=1
                        READY=0
                        while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
                            if ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "echo ok" 2>/dev/null; then
                                READY=1
                                break
                            fi
                            sleep 5
                            ATTEMPT=$((ATTEMPT + 1))
                        done
                        [ "$READY" = "1" ] || { echo "ERROR: SSH never became reachable."; exit 1; }
                    '''
                }
            }
        }

        stage('Ansible Provision') {
            when { expression { env.SKIP_PROVISION != 'true' } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'es2-ssh', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER')]) {
                    sh '''
                        chmod 600 "$SSH_KEY"
                        EC2_IP=$(cd jinkins/terraform && terraform output -raw instance_public_ip)
                        sed -e "s|__EC2_IP__|$EC2_IP|" \
                            -e "s|__SSH_USER__|$SSH_USER|" \
                            -e "s|__SSH_KEY_PATH__|$SSH_KEY|" \
                            jinkins/config-server/inventory.ini.template > jinkins/config-server/inventory.ini

                        docker run --rm \
                            -e ANSIBLE_HOST_KEY_CHECKING=False \
                            -v "$PWD":"$PWD" \
                            -v "$SSH_KEY":"$SSH_KEY":ro \
                            -w "$PWD" \
                            quay.io/ansible/ansible-runner:latest \
                            ansible-playbook -i jinkins/config-server/inventory.ini jinkins/config-server/site.yml
                    '''
                }
            }
        }

        stage('Deploy Application') {
            steps {
                script {
                    env.DEPLOYMENT_STARTED = 'true'
                }
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'es2-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'SSH_USER'
                    ),
                    usernamePassword(
                        credentialsId: 'docker-hub',
                        usernameVariable: 'DOCKERHUB_USERNAME',
                        passwordVariable: 'DOCKERHUB_TOKEN'
                    ),
                    usernamePassword(
                        credentialsId: 'users-app-db',
                        usernameVariable: 'DB_USER',
                        passwordVariable: 'DB_PASSWORD'
                    ),
                    file(
                        credentialsId: 'db-init-sql',
                        variable: 'INIT_SQL_FILE'
                    )
                ]) {
                    sh '''
                        chmod 600 "$SSH_KEY"

                        EC2_IP=$(cd jinkins/terraform && terraform output -raw instance_public_ip)
                        APP_IMAGE="$DOCKERHUB_USERNAME/$APP_NAME:$BUILD_NUMBER"

                        echo "Deploying application to EC2: $EC2_IP"
                        echo "Application image: $APP_IMAGE"

                        ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "mkdir -p ~/$APP_NAME"

                        echo "Copying docker-compose.yml (contains no secrets)..."
                        scp -o StrictHostKeyChecking=no -i "$SSH_KEY" \
                            jinkins/user-app/docker-compose.yml "$SSH_USER@$EC2_IP:~/$APP_NAME/docker-compose.yml"

                        REMOTE_ENV_EXISTS=$(ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" \
                            "[ -f ~/$APP_NAME/.env ] && echo yes || echo no")

                        if [ "$REMOTE_ENV_EXISTS" = "no" ]; then
                            echo "First-time deploy on this server - copying init.sql and creating .env..."

                            scp -o StrictHostKeyChecking=no -i "$SSH_KEY" \
                                "$INIT_SQL_FILE" "$SSH_USER@$EC2_IP:~/$APP_NAME/init.sql"

                            ENV_TMP_FILE=$(mktemp)
                            {
                                printf 'APP_IMAGE=%s\n' "$APP_IMAGE"
                                printf 'POSTGRES_DB=%s\n' "$DB_NAME"
                                printf 'POSTGRES_USER=%s\n' "$DB_USER"
                                printf 'POSTGRES_PASSWORD=%s\n' "$DB_PASSWORD"
                                printf 'DB_HOST=%s\n' "$DB_HOST"
                                printf 'DB_PORT=%s\n' "$DB_PORT"
                                printf 'DB_NAME=%s\n' "$DB_NAME"
                                printf 'DB_USER=%s\n' "$DB_USER"
                                printf 'DB_PASSWORD=%s\n' "$DB_PASSWORD"
                            } > "$ENV_TMP_FILE"

                            scp -o StrictHostKeyChecking=no -i "$SSH_KEY" "$ENV_TMP_FILE" "$SSH_USER@$EC2_IP:~/$APP_NAME/.env"
                            rm -f "$ENV_TMP_FILE"

                            ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "chmod 600 ~/$APP_NAME/.env"
                            echo "Created .env and init.sql for the first time."
                        else
                            echo "Routine update - init.sql and DB credentials are NOT touched."
                            ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" bash -s << REMOTE_EOF
sed -i "s|^APP_IMAGE=.*|APP_IMAGE=$APP_IMAGE|" ~/$APP_NAME/.env
REMOTE_EOF
                            echo ".env already existed - only updated APP_IMAGE."
                        fi

                        echo "Deploying app container only (--no-deps keeps postgres untouched)..."
                        ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" \
                            "cd ~/$APP_NAME && \
                             sudo docker compose pull app && \
                             sudo docker compose up -d --no-deps --force-recreate app && \
                             sudo docker ps"
                    '''
                }
            }
        }

        stage('Apply Schema Changes If Needed') {
            // Runs AFTER Deploy Application, so postgres is guaranteed to be running.
            // Detects whether init.sql actually changed since last time, and only
            // then applies it - directly against the live database, no container
            // recreation, no data loss.
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'es2-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'SSH_USER'
                    ),
                    file(
                        credentialsId: 'db-init-sql',
                        variable: 'INIT_SQL_FILE'
                    )
                ]) {
                    sh '''
                        chmod 600 "$SSH_KEY"
                        EC2_IP=$(cd jinkins/terraform && terraform output -raw instance_public_ip)

                        LOCAL_HASH=$(sha256sum "$INIT_SQL_FILE" | awk '{print $1}')
                        REMOTE_HASH=$(ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" \
                            "cat ~/$APP_NAME/.schema-hash 2>/dev/null || echo none")

                        echo "Local schema hash:  $LOCAL_HASH"
                        echo "Remote schema hash: $REMOTE_HASH"

                        if [ "$LOCAL_HASH" = "$REMOTE_HASH" ]; then
                            echo "Schema unchanged - nothing to do, database left untouched."
                        else
                            echo "Schema file changed - applying it to the live database..."

                            scp -o StrictHostKeyChecking=no -i "$SSH_KEY" \
                                "$INIT_SQL_FILE" "$SSH_USER@$EC2_IP:~/$APP_NAME/init.sql"

                            ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" \
                                "cd ~/$APP_NAME && sudo docker compose exec -T postgres psql -U postgres -d ${DB_NAME} < init.sql"

                            ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" \
                                "echo $LOCAL_HASH > ~/$APP_NAME/.schema-hash"

                            echo "Schema updated successfully - existing data was not deleted."
                        fi
                    '''
                }
            }
        }

        stage('Health Check') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'es2-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'SSH_USER'
                    )
                ]) {
                    sh '''
                        chmod 600 "$SSH_KEY"

                        EC2_IP=$(cd jinkins/terraform && terraform output -raw instance_public_ip)

                        MAX_ATTEMPTS=5
                        ATTEMPT=1
                        HEALTHY=0

                        while [ $ATTEMPT -le $MAX_ATTEMPTS ]; do
                            echo "Health check attempt $ATTEMPT/$MAX_ATTEMPTS..."

                            HEALTH_RESPONSE=$(ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "curl -s http://localhost:5000/health" || true)
                            USERS_RESPONSE=$(ssh -o StrictHostKeyChecking=no -i "$SSH_KEY" "$SSH_USER@$EC2_IP" "curl -s http://localhost:5000/users" || true)

                            if echo "$HEALTH_RESPONSE" | grep -q '"status":"ok"' && \
                               echo "$USERS_RESPONSE" | grep -q "Fatma" && \
                               echo "$USERS_RESPONSE" | grep -q "Elias"; then
                                echo "Application tests passed successfully."
                                HEALTHY=1
                                break
                            fi

                            echo "Not healthy yet, waiting 5s..."
                            sleep 5
                            ATTEMPT=$((ATTEMPT + 1))
                        done

                        if [ "$HEALTHY" != "1" ]; then
                            echo "ERROR: Application failed health check after $MAX_ATTEMPTS attempts."
                            exit 1
                        fi
                    '''
                }
            }
        }

        stage('Save Successful Deployment') {
            steps {
                sh '''
                    echo "${BUILD_NUMBER}" > last_successful_tag.txt
                    echo "Recording ${BUILD_NUMBER} as last known-good deployment"
                    mkdir -p "${JENKINS_HOME}/users-app-state"
                    cp last_successful_tag.txt "${JENKINS_HOME}/users-app-state/last_successful_tag.txt"
                '''
                archiveArtifacts artifacts: 'last_successful_tag.txt', fingerprint: true
            }
        }
    }

    post {
            failure {
                script {
                    if (env.DEPLOYMENT_STARTED != 'true') {
                        echo "Failure happened before deployment actually started - old app is still running. Stopping without rollback."
                        return
                    }

                    def stateFile = "${env.JENKINS_HOME}/users-app-state/last_successful_tag.txt"

                    if (!fileExists(stateFile)) {
                        echo "No previous successful deployment recorded - cannot auto-rollback."
                        return
                    }

                    def previousTag = readFile(stateFile).trim()

                    if (!previousTag || previousTag == env.BUILD_NUMBER) {
                        echo "No different previous version to roll back to (same tag that failed)."
                        return
                    }

                    echo "Pipeline failed on tag ${env.BUILD_NUMBER}. Rolling back to last known-good tag: ${previousTag}"

                    withCredentials([
                        sshUserPrivateKey(credentialsId: 'es2-ssh', keyFileVariable: 'SSH_KEY', usernameVariable: 'SSH_USER'),
                        usernamePassword(credentialsId: 'docker-hub', usernameVariable: 'DOCKERHUB_USERNAME', passwordVariable: 'DOCKERHUB_TOKEN')
                    ]) {
                        sh """
                            chmod 600 "\$SSH_KEY"

                            EC2_IP=\$(cd jinkins/terraform && terraform output -raw instance_public_ip)
                            ROLLBACK_IMAGE="\$DOCKERHUB_USERNAME/${env.APP_NAME}:${previousTag}"

                            echo "Rolling back EC2 (\$EC2_IP) to \$ROLLBACK_IMAGE"

                            ssh -o StrictHostKeyChecking=no -i "\$SSH_KEY" "\$SSH_USER@\$EC2_IP" "
                                sed -i 's|^APP_IMAGE=.*|APP_IMAGE=\$ROLLBACK_IMAGE|' ~/${env.APP_NAME}/.env
                                cd ~/${env.APP_NAME} && \
                                sudo docker compose pull app && \
                                sudo docker compose up -d --no-deps --force-recreate app && \
                                sudo docker ps
                            "

                            echo "Verifying rollback (health + users)..."
                            ROLLBACK_HEALTH=\$(ssh -o StrictHostKeyChecking=no -i "\$SSH_KEY" "\$SSH_USER@\$EC2_IP" "curl -s http://localhost:5000/health")
                            ROLLBACK_USERS=\$(ssh -o StrictHostKeyChecking=no -i "\$SSH_KEY" "\$SSH_USER@\$EC2_IP" "curl -s http://localhost:5000/users")

                            if echo "\$ROLLBACK_HEALTH" | grep -q '"status":"ok"' && echo "\$ROLLBACK_USERS" | grep -q "Fatma"; then
                                echo "Rollback succeeded. EC2 is back on tag ${previousTag}."
                            else
                                echo "Rollback health/users check FAILED. Manual intervention required."
                            fi
                        """
                    }
                }
            }
        }
}




    
