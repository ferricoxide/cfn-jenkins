#!/bin/bash
# shellcheck disable=SC2005,SC2059,SC2015
#
# Script to install and configure Jenkins and related components
#
#################################################################
# shellcheck disable=SC2086
PROGNAME="$(basename ${0})"
while read -r JNKENV
# Read args from envs file
do
   # shellcheck disable=SC2163
   export "${JNKENV}"
done < /etc/cfn/Jenkins.envs
SELMODE="$(awk -F= '/^SELINUX=/{print $2}' /etc/selinux/config)"
JENKDATADIR="${JENKINS_HOME_PATH:-UNDEF}"
JENKBKUPBKT="${JENKINS_BACKUP_BUCKET:-UNDEF}"
JENKBKUPFLD="${JENKINS_BACKUP_FOLDER:-UNDEF}"
JENKHOMEURL="s3://${JENKBKUPBKT}/${JENKBKUPFLD}"
JENKINITTOK="${JENKDATADIR}/secrets/initialAdminPassword"
JENKRPM=${JENKINS_RPM_NAME:-UNDEF}

##
## Set up an error logging and exit-state
function err_exit {
   local ERRSTR="${1}"
   local SCRIPTEXIT=${2:-1}

   # Our output channels
   echo "${ERRSTR}" > /dev/stderr
   logger -t "${PROGNAME}" -p kern.crit "${ERRSTR}"

   # Need our exit to be an integer
   if [[ ${SCRIPTEXIT} =~ ^[0-9]+$ ]]
   then
      exit "${SCRIPTEXIT}"
   else
      exit 1
   fi
}

##
## Decide what Jenkins version to install
function InstJenkins {
   local CPUARCH
      CPUARCH=$(uname -i)
   local RPMARR
      RPMARR=(
       $(
         yum --showduplicates list available "${JENKRPM}" | \
         tail -1
        )
      )

   if [[ ${#RPMARR[@]} -gt 0 ]]
   then
      yum install -qy "${RPMARR[0]/.${CPUARCH}/}-${RPMARR[1]}.${CPUARCH}"
   else
      err_exit 'Was not able to determine Jenkins version to install'
   fi
}

##
## See if there's a valid restore archive to rebuild from
function TryRestore {
   local RESTOREFILE

   # Restore JENKINS_HOME content (if available)
   if [[ $(aws s3 ls ${JENKHOMEURL} > /dev/null 2>&1 )$? -gt 0 ]]
   then
      printf 'Cannot find a recovery-directory in %s\n' "${JENKHOMEURL}"
      return
   fi

   # Lookd for TAR files; use last-written
   RESTOREFILE="$(aws s3 ls ${JENKHOMEURL}/sync/ | grep tar$ | sort | tail -1 | awk '{print $4}')"

   # Ensure a auto-recovery TAR file was found
   if [[ -z ${RESTOREFILE} ]]
   then
      echo "Could not find an S3-hosted tar-file to auto-recover from"
      return
   fi

   # Validate the TAR file
   printf "Attempting to validate %s... " "${RESTOREFILE}"
   if [[ $( aws s3 cp "${JENKHOMEURL}/sync/${RESTOREFILE}" - | tar tf - > /dev/null )$? -ne 0 ]]
   then
      echo "Errors found. Bailing... "
      return
   else
      echo "File appears clean. Continuing... "
   fi

   # Try to restore from TAR file
   echo "Attempting to restore JENKINS_HOME from S3... "
   /usr/bin/aws s3 cp ${JENKHOMEURL}/sync/${RESTOREFILE} - | tar -C / --checkpoint=100000 -xf -

}


# Install Jenkins from RPM/yum
yum install -y "${JENKRPM}" || err_exit 'Jenkins install failed'

#####
## Ensure that Jenkins uses a safe TEMP-dir

printf "Backing up /etc/sysconfig/jenkins... "
install -b -m 000600 /etc/sysconfig/jenkins /etc/sysconfig/jenkins.bak && \
  echo "Success" || err_exit "Failed to back up /etc/sysconfig/jenkins"

printf "Relocating Jenkins's TEMP-dir... "
# shellcheck disable=SC2016
sed -i '/^JENKINS_JAVA_OPTIONS/s/"$/ -Djava.io.tmpdir=$JENKINS_HOME\/tmp"/' /etc/sysconfig/jenkins && \
  echo "Success" || err_exit "Failed to relocate Jenkins's TEMP-dir"

printf "Creating Jenkins's safe TEMP-dir... "
install -d -m 000750 -o jenkins -g jenkins /var/lib/jenkins/tmp && \
  echo "Success" || err_exit "Failed to create Jenkins's safe TEMP-dir"

##
#####

# Attempt to auto-recover from prior instantiation
TryRestore

# Verify auto-recovery status
if [[ -f ${JENKDATADIR}/config.xml ]]
then
   echo "Restored JENKINS_HOME from S3."
   FRESHINSTALL=1
else
   echo "No restorable S3 content found. Assuming this is a fresh Install."
   FRESHINSTALL=0
fi

# Start Jenkins
echo "Starting and enabling Jenkins service... "
systemctl enable jenkins
systemctl start jenkins

# Re-enable SELinux
printf "Reverting SELinux enforcing-mode... "
# shellcheck disable=SC2015
setenforce "${SELMODE}" && echo || err_exit 'Could not change SEL-mode'

# Display initial admin token on fresh install first-boot
if [[ ${FRESHINSTALL} -eq 0 ]]
then
   while [[ ! -f ${JENKINITTOK} ]]
   do
      echo "Sleeping for 15s to allow secrets-file to populate... "
      sleep 15
   done

   echo "########################################"
   echo "##"
   echo "## Jenkins unlock-string is:"
   printf "##   * "
   echo "$(cat ${JENKINITTOK})"
   echo "##"
   echo "########################################"

   aws s3 cp "${JENKINITTOK}" s3://"${JENKBKUPBKT}/admin-info/"
fi
