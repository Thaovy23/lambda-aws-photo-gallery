# Backup Before RDS Integration

This folder contains the original versions of files before implementing RDS MySQL integration with Lambda.

## Original Architecture (S3 Only)
- Upload: LambdaUploadObject → S3
- List: LambdaGetListOfObjects → list from S3
- Delete: LambdaDeleteObject → delete from S3
- Get: LambdaGetObject → get from S3

## Files Backed Up
- `index_original.html` - Frontend before RDS integration
- `LambdaUploadObject_original.java` - Original upload Lambda
- `LambdaDeleteObject_original.java` - Original delete Lambda
- `LambdaGetListOfObjects_original.java` - Original list Lambda

## New Architecture (S3 + RDS)
- Upload: LambdaUploadWithDescription (orchestrator) → LambdaUploadObject (S3) + LambdaInsertPhoto (DB)
- List: LambdaGetPhotosDB → query from MySQL
- Delete: LambdaDeletePhoto (orchestrator) → LambdaDeleteObject (S3) + LambdaDeletePhotoDB (DB)
- Get: LambdaGetObject → get from S3 (unchanged)

## Date
December 2, 2025



