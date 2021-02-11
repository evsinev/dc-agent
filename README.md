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

Where
* $CI_COMMIT_REF_NAME-$CI_JOB_ID – the artifact version
* $UPLOAD_KE – the access key

After executing this command you should get the file on your server at the ```/opt/sbp-android/master-216018.apk``` path

