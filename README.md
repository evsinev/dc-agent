# Utility http agent for various tasks

Tasks
* Upload zip archive with configs
* Fetch url
* Save artifact

## Save artifact

The config file is

```json
{
  "apiKeys": {
    "$UPLOAD_KEY": "gitlab-ci"
  },
  "dir": "/opt/sbp-android",
  "extension" : "apk"
}
```

The upload command
```sh
curl \
  --data-binary @app/build/outputs/apk/release/app-release.apk \
  --fail \
  -H "api-key: $UPLOAD_KEY" \
  https://db-agent-host/dc-agent/save-artifact/sbp-android/$CI_COMMIT_REF_NAME-$CI_JOB_ID'
```
Parameters

| Parameter name | Description            |
| -------------- | ---------------------- |
| dir            | Directory to this file |
| extension      | File extension         |

After executing this command you should get the file on your server at the ```/opt/sbp-android/master-216018.apk``` path

## Upload zip file with configs

Place this json file to the ./config/app-name.json path

```json
{
  "apiKeys": {
    "$UPLOAD_KEY": "gitlab-ci-token"
  },
  "dir": "/opt/$APP_NAME/config"
}
```

Create a zip file with configs:
```sh
jar cfM configs.zip -C configs/ .
```

The command to upload configs:

```sh
curl \
  --data-binary @configs.zip \
  --fail \
  -H "api-key: ${UPLOAD_KEY}" \
  https://dc-agent-host/dc-agent/zip-archive/$APP_NAME
```

## war, jar, node

| Parameter           | Description                        | Default Value                 |
| ------------------- | ---------------------------------- | ----------------------------- |
| warFilename         | Archive filename                   |                               |
| jarFilename         | Archive filename                   |                               |
| serviceName         | Service name                       |                               |
| serviceDir          | Service directory                  | /service/$serviceName         |
| serviceStopTimeout  | Stop timeout for svc command       | 30s                           |
| serviceStartTimeout | Start timeout for svc command      | 10s                           |
| serviceLogFile      | Service log file                   | /var/log/$serviceName/current |
| waitUrl             | Wait url. Should return 200 status |                               |
| waitDuration        | Wait stage duration                | 3m                            |
| waitConnectTimeout  | Wait url connection timeout        | 10s                           |
| waitReadTimeout     | Wait url read timeout              | 30s                           |
| svcCommand          | Path to svc command                | /usr/bin/svc                  |
| svstatCommand       | Path to svstat command             | /usr/bin/svstat               |

