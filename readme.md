# route53-acme-helper

Use this to resolve ACME DNS-01 challenges on a different machine than the one running certbot.

## Building

Use openjdk-11. Run `mvn clean package`. Find the artifact at target/route53-acme-helper.jar.

## Usage

### Set up route53-acme-helper

Run the app with the following IAM policy:
```json
{
   "Version": "2017-11-27",
   "Statement":[
     {
       "Effect": "Allow",
       "Action": [
         "route53:ListResourceRecordSets",
         "route53:ChangeResourceRecordSets"
       ],
       "Resource": [
         "arn:aws:route53:::hostedzone/<ZONE_ID>"
       ]
     },
     {
       "Effect": "Allow",
       "Action": [
         "route53:GetChange"
       ],
       "Resource": [
         "*"
       ]
     }
   ]   
}
```

Create /etc/r53-helper-auth.properties with a token for each host that needs access:
```
random-token=comma-separated-list-of-domains
cmFuZG9tLXRva2Vu=my.example.com
```

Create /etc/systemd/system/r53-helper.service:
```
[Unit]
After=network-online.target
[Service]
ExecStart=/usr/lib/jvm/java-11-openjdk-amd64/bin/java \
  -Dport=80 -Dr53zone=<ZONE_ID> -Dauth=/etc/r53-helper-auth.properties \
  -jar /opt/route53-acme-helper.jar
SuccessExitStatus=143
[Install]
WantedBy=multi-user.target
```

Then start the service:
```bash
systemctl daemon-reload
systemctl enable r53-helper
systemctl start r53-helper
```

### Configure and run certbot

The following assumes route53-acme-helper is reachable at https://route53-acme-helper.example.com/

Create and `chmod +x` authenticator.sh and cleanup.sh on the host where you run certbot.

/usr/local/bin/r53-authenticator.sh:
```bash
#!/bin/bash
set -e

. /etc/letsencrypt/r53-secrets
curl -fsS -XPOST \
  -H "Authorization: Bearer $TOKEN" \
  -H "CERTBOT_DOMAIN: $CERTBOT_DOMAIN" \
  -H "CERTBOT_VALIDATION: $CERTBOT_VALIDATION" \
  https://route53-acme-helper.example.com/
```

/usr/local/bin/r53-cleanup.sh:
```bash
#!/bin/bash
set -e

. /etc/letsencrypt/r53-secrets
curl -fsS -XDELETE \
  -H "Authorization: Bearer $TOKEN" \
  -H "CERTBOT_DOMAIN: $CERTBOT_DOMAIN" \
  -H "CERTBOT_VALIDATION: $CERTBOT_VALIDATION" \
  https://route53-acme-helper.example.com/
```
               
Create and `chmod 600` /etc/letsencrypt/r53-secrets:
```
# a valid token from auth.properties
TOKEN=cmFuZG9tLXRva2Vu
```
                          
Run certbot:
```bash
certbot certonly -d my.example.com \
  --manual --preferred-challenges=dns \
  --manual-auth-hook /usr/local/bin/r53-authenticator.sh \
  --manual-cleanup-hook /usr/local/bin/r53-cleanup.sh
```
