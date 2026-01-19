# LambdaAWS

## Lambda & IAM Permissions

### LambdaUploadWithDescription
- IAM Role permissions:
  - `lambda:InvokeFunction` on `LambdaUploadObject` and `LambdaInsertPhoto`
  - (Optional) CloudWatch logs permissions for logging
- Environment variables:
  - `UPLOAD_FUNCTION_NAME` = name of Lambda that uploads to S3
  - `INSERT_FUNCTION_NAME` = name of Lambda that inserts DB row

### LambdaDeletePhoto
- IAM Role permissions:
  - `lambda:InvokeFunction` on `LambdaDeleteObject` and `LambdaDeletePhotoDB`
- Environment variables:
  - `DELETE_OBJECT_FUNCTION_NAME` = name of Lambda that deletes S3 object
  - `DELETE_DB_FUNCTION_NAME` = name of Lambda that deletes row in DB

### LambdaInsertPhoto / LambdaDeletePhotoDB / LambdaGetPhotosDB
- IAM Role permissions:
  - `rds-db:connect` for the specific RDS instance and DB user (see policy example below)
  - `rds:DescribeDBInstances` (optional)
  - CloudWatch logs permissions

#### Example IAM policy for RDS token auth
```
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "rds-db:connect",
      "Resource": "arn:aws:rds-db:<REGION>:<ACCOUNT_ID>:dbuser:<DB_RESOURCE_ID>/<DB_USER>"
    },
    {
      "Effect": "Allow",
      "Action": "rds:DescribeDBInstances",
      "Resource": "*"
    }
  ]
}
```

Replace `<REGION>`, `<ACCOUNT_ID>`, `<DB_RESOURCE_ID>`, `<DB_USER>` with your values:
1. `REGION`: e.g. `ap-southeast-1`
2. `ACCOUNT_ID`: AWS account ID
3. `DB_RESOURCE_ID`: from RDS console → Configuration → Resource ID
4. `DB_USER`: MySQL user (e.g. `cloud26`)

### Other IAM notes
- Lambdas that access S3 directly (e.g. `LambdaUploadObject`, `LambdaDeleteObject`, `LambdaGetObject`) need `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject` on the buckets used (`public-mg2`, `resizebucket-vy`, ...)
- Ensure CloudWatch Logs permissions (`logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`) for all Lambda roles
