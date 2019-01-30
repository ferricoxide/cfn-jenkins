pipeline {

agent any

options {
    buildDiscarder(
        logRotator(
            numToKeepStr: '5',
            daysToKeepStr: '30',
            artifactDaysToKeepStr: '30',
            artifactNumToKeepStr: '3'
        )
    )
    disableConcurrentBuilds()
    timeout(time: 60, unit: 'MINUTES')
}

environment {
    AWS_DEFAULT_REGION = "${AwsRegion}"
    AWS_CA_BUNDLE = '/etc/pki/tls/certs/ca-bundle.crt'
    REQUESTS_CA_BUNDLE = '/etc/pki/tls/certs/ca-bundle.crt'
}

parameters {
    string(name: 'AwsRegion', defaultValue: 'us-east-1', description: 'Amazon region to deploy resources into')
    string(name: 'AwsCred', description: 'Jenkins-stored AWS credential with which to execute cloud-layer commands')
    string(name: 'GitCred', description: 'Jenkins-stored Git credential with which to execute git commands')
    string(name: 'GitProjUrl', description: 'SSH URL from which to download the Jenkins git project')
    string(name: 'GitProjBranch', description: 'Project-branch to use from the Jenkins git project')
    string(name: 'CfnStackRoot', description: 'Unique token to prepend to all stack-element names')
    string(name: 'TemplateUrl', description: 'S3-hosted URL for the EC2 template file')
    string(name: 'BackendTimeout', defaultValue: '600', description: 'How long - in seconds - back-end connection may be idle before attempting session-cleanup')
    string(name: 'JenkinsAgentPort', description: 'TCP Port number that the Jenkins agent-hosts connect through')
    string(name: 'JenkinsListenerCert', defaultValue: '', description: 'Name/ID of the ACM-managed SSL Certificate to protect public listener')
    string(name: 'JenkinsListenPort', defaultValue: '443', description: 'TCP Port number on which the Jenkins ELB listens for requests')
    string(name: 'JenkinsServicePort', defaultValue: '8080', description: 'TCP Port number that the Jenkins host listens to')
    string(name: 'JenkinsPassesSsh', defaultValue: 'false', description: 'Whether to allow SSH passthrough to Jenkins master')
    string(name: 'HaSubnets', description: 'Select three subnets - each from different Availability Zones')
    string(name: 'ProxyPrettyName', description: 'A short, human-friendly label to assign to the ELB - no capital letters')
    string(name: 'SecurityGroupIds', description: 'List of security groups to apply to the ELB')

}

stages {
    stage ('Prepare Jenkins Workspace') {
        steps {
            deleteDir()
            git branch: "${GitProjBranch}",
                credentialsId: "${GitCred}",
                url: "${GitProjUrl}"
            writeFile file: 'master.ec2.instance.parms.json',
                text: /
                  [
                      {
                        "ParameterKey": "BackendTimeout",
                        "ParameterValue": "${env.BackendTimeout}"
                      },
                      {
                        "ParameterKey": "JenkinsAgentPort",
                        "ParameterValue": "${env.JenkinsAgentPort}"
                      },
                      {
                        "ParameterKey": "JenkinsListenerCert",
                        "ParameterValue": "${env.JenkinsListenerCert}"
                      },
                      {
                        "ParameterKey": "JenkinsListenPort",
                        "ParameterValue": "${env.JenkinsListenPort}"
                      },
                      {
                        "ParameterKey": "JenkinsServicePort",
                        "ParameterValue": "${env.JenkinsServicePort}"
                      },
                      {
                        "ParameterKey": "JenkinsPassesSsh",
                        "ParameterValue": "${env.JenkinsPassesSsh}"
                      },
                      {
                        "ParameterKey": "HaSubnets",
                        "ParameterValue": "${env.HaSubnets}"
                      },
                      {
                        "ParameterKey": "ProxyPrettyName",
                        "ParameterValue": "${env.ProxyPrettyName}"
                      },
                      {
                        "ParameterKey": "SecurityGroupIds",
                        "ParameterValue": "${env.SecurityGroupIds}"
                      }
                  ]
               /
            }
        }
    stage ('Prepare AWS Environment') {
        steps {
            withCredentials(
                [
                    [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: "${AwsCred}", secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
                    sshUserPrivateKey(credentialsId: "${GitCred}", keyFileVariable: 'SSH_KEY_FILE', passphraseVariable: 'SSH_KEY_PASS', usernameVariable: 'SSH_KEY_USER')
                ]
            ) {
                sh '''#!/bin/bash
                    echo "Attempting to delete any active ${CfnStackRoot}-ELbv1 stacks... "
                    aws --region "${AwsRegion}" cloudformation delete-stack --stack-name "${CfnStackRoot}-ELbv1"

                    aws cloudformation wait stack-delete-complete --stack-name ${CfnStackRoot}-ELbv1 --region ${AwsRegion}
                '''
            }
        }
    }
    stage ('Launch Jenkins ELB Stack') {
        steps {
            withCredentials(
                [
                    [$class: 'AmazonWebServicesCredentialsBinding', accessKeyVariable: 'AWS_ACCESS_KEY_ID', credentialsId: "${AwsCred}", secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'],
                    sshUserPrivateKey(credentialsId: "${GitCred}", keyFileVariable: 'SSH_KEY_FILE', passphraseVariable: 'SSH_KEY_PASS', usernameVariable: 'SSH_KEY_USER')
                ]
            ) {
                sh '''#!/bin/bash
                    echo "Attempting to create stack ${CfnStackRoot}-ELbv1..."
                    aws --region "${AwsRegion}" cloudformation create-stack --stack-name "${CfnStackRoot}-ELbv1" \
                      --disable-rollback --capabilities CAPABILITY_NAMED_IAM \
                      --template-url "${TemplateUrl}" \
                      --parameters file://master.ec2.instance.parms.json

                    aws cloudformation wait stack-create-complete --stack-name ${CfnStackRoot}-ELbv1 --region ${AwsRegion}
                '''
            }
        }
    }
  }
}
