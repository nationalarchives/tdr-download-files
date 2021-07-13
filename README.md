## Download Files From S3

This is a lambda which takes the messages from the S3 uploads and downloads them to the EFS volume, where they can be reused by other processes.

### Adding new environment variables to the tests
The environment variables in the deployed lambda are encrypted using KMS and then base64 encoded. These are then decoded in the lambda. Because of this, any variables in `src/test/resources/application.conf` which come from environment variables in `src/main/resources/application.conf` need to be stored base64 encoded. There are comments next to each variable to say what the base64 string decodes to. If you want to add a new variable you can run `echo -n "value of variable" | base64 -w 0` and paste the output into the test application.conf

### Local development

Set the following environment variables, either on the command line or in an IntelliJ run configuration, depending on
where you want to run the app. The suggested values assume that you want to run the Lambda against the integration
environment.

- `INPUT_QUEUE`: The URL of the download files SQS queue. You can get a list of all queue URLs by running
  `aws sqs list-queues`
- `ANTIVIRUS_QUEUE`: The URL of the antivirus SQS queue
- `CHECKSUM_QUEUE`: The URL of the checksum SQS queue
- `FILE_FORMAT_QUEUE`: The URL of the file format SQS queue
- `AWS_LAMBDA_FUNCTION_NAME`: The name of the download files Lambda, which on intg is `tdr-download-files-intg`
- `ROOT_DIRECTORY`: A directory on your own machine that the S3 files will be downloaded to, e.g.
  `/tmp/test-download-files`.
- `CLIENT_ID`: The Keycloak client ID of the file checks client, which is `tdr-backend-checks`
- `CLIENT_SECRET`: The secret key for the Keycloak client, which you can look up in Keycloak on intg or by running
  `aws ssm get-parameter --name "/intg/keycloak/backend_checks_client/secret" --with-decryption`
- `API_URL`: The URL of the GraphQL API endpoint, which on intg is
  `https://api.tdr-integration.nationalarchives.gov.uk/graphql`

Log into AWS SSO, and copy credentials for intg to your AWS credentials file.

On the command line, run `sbt run`. In IntelliJ, run the `LambdaRunner` app.

The `LambdaRunner` hard-codes a message for a specific file ID. This corresponds to a small text file that was uploaded
to intg. You can use a different file ID, but you'll need to set the consignment ID and Cognito user ID to the correct
values for the file so that they match the object ID in S3.

Running the `LambdaRunner` will add messages to the downstream file check queues, so the real file checks will start on
the AWS environment. If you want to avoid this, set the three file check queue environment variables to the URL of the
failure queue instead.
