## Serverless Photo Gallery on AWS (Lambda + Java)

A serverless photo gallery built on AWS that supports token-based login, secure photo uploads, automatic thumbnail generation, and a simple bucket-like web UI.

It demonstrates an end-to-end architecture using **AWS Lambda (Java + AWS SDK v2)**, **Lambda Function URLs**, **S3**, **RDS MySQL**, **Systems Manager Parameter Store**, and **IAM least-privilege permissions**.

---

## What the app does

### 1) Login with email-based token
- `LambdaGenerateToken` generates a token from the user’s email and a secret key stored in Parameter Store (HMAC-SHA256).
- `LambdaVerifyToken` validates the token for every protected operation.

### 2) Upload photos with description
- Frontend (`index.html`) converts the image to Base64 and sends it (plus description + token) to `LambdaUploadWithDescription`.
- `LambdaUploadWithDescription`:
  - verifies the token
  - hashes the filename to create a stable S3 key
  - invokes downstream Lambdas to:
    - upload the original image to S3 bucket `public-mg2` (`LambdaUploadObject`)
    - generate a resized thumbnail and store it in `resizebucket-vy` (`LambdaResizer`)
    - insert metadata (S3 key, description, email) into an RDS MySQL `Photos` table (`LambdaInsertPhoto`)

### 3) View gallery
- `LambdaGetPhotosDB` reads from the `Photos` table and returns a list of photos.
- The web UI renders a table with thumbnails, description, owner email, and actions.

### 4) Download and delete
- `LambdaGetObject` returns image bytes (original or resized) for preview/download.
- `LambdaDeletePhoto` orchestrates deletion in parallel:
  - S3 deletion (`LambdaDeleteObject`)
  - DB row deletion (`LambdaDeletePhotoDB`)

---

## Project goals

- Demonstrate a complete serverless workflow: authentication, upload, processing (thumbnail generation), storage, and retrieval.
- Apply least-privilege IAM permissions for Lambda access to S3, RDS, and SSM Parameter Store.
- Keep the system modular by separating responsibilities across Lambda functions (token, upload, resize, DB metadata, retrieval, deletion orchestration).
- Address practical serverless concerns such as cold starts via an optional warm-up schedule (EventBridge/CloudWatch).

---

## Tech stack

- **Frontend**: Vanilla HTML, CSS, JavaScript (`index.html`)
- **Backend**: AWS Lambda functions in **Java** (AWS SDK v2)
- **Storage**
  - **S3**: originals (`public-mg2`) and thumbnails (`resizebucket-vy`)
  - **RDS MySQL**: `Photos` table (S3 key, description, owner email)
- **Security & config**
  - **SSM Parameter Store**: secret key for HMAC token (`keytokenhash`)
  - **IAM roles & policies**: Lambda → S3, Lambda → RDS, Lambda → Parameter Store
- **HTTP access**
  - **Lambda Function URLs** for direct calls from the browser
- **Optional**
  - **LambdaTimer** (EventBridge/CloudWatch) to warm up functions and reduce cold starts

---

## Architecture overview

High-level flow:

1. User opens the static `index.html` (locally or hosted via S3/CloudFront).
2. User enters email → `LambdaGenerateToken` → receives token.
3. User uploads image + description → `LambdaUploadWithDescription`:
   - verifies token (`LambdaVerifyToken`)
   - uploads original to S3 (`LambdaUploadObject`)
   - generates and uploads thumbnail (`LambdaResizer`)
   - writes metadata to RDS (`LambdaInsertPhoto`)
4. User clicks **View My Bucket** → `LambdaGetPhotosDB` → gallery table is rendered.
5. Thumbnails/originals load on-demand via `LambdaGetObject`.
6. User can **Download** or **Delete** via `LambdaDeletePhoto`.

![Architecture](assets/architecture-diagram.png)

Optional sequence diagram (useful for interviews / debugging the flow):

![Upload Sequence](assets/sequence-upload.png)

---

## Lambda functions

### Authentication
- `LambdaGenerateToken`: issues a token from `{email + secret}` using HMAC-SHA256
- `LambdaVerifyToken`: verifies token validity for protected operations

### Upload pipeline
- `LambdaUploadWithDescription`: main entrypoint for upload + description
- `LambdaUploadObject`: uploads original image to S3
- `LambdaResizer`: generates and uploads thumbnail to a separate bucket
- `LambdaInsertPhoto`: inserts metadata into RDS MySQL

### Gallery / retrieval
- `LambdaGetPhotosDB`: queries `Photos` table and returns photo list
- `LambdaGetObject`: fetches and returns image bytes (original/thumbnail)

### Deletion
- `LambdaDeletePhoto`: orchestrates deletion across services
- `LambdaDeleteObject`: deletes object(s) from S3
- `LambdaDeletePhotoDB`: deletes metadata row from RDS

---

## Configuration

### S3 buckets
- `public-mg2`: stores original images
- `resizebucket-vy`: stores resized thumbnails

### RDS
- MySQL with table `Photos` (example fields)
  - `s3_key`
  - `description`
  - `email`

### Parameter Store
- `keytokenhash`: secret used to generate/verify HMAC tokens

---

## Lambda & IAM permissions (security highlights)

### LambdaUploadWithDescription
**IAM role permissions**
- `lambda:InvokeFunction` on:
  - `LambdaUploadObject`
  - `LambdaInsertPhoto`
  - `LambdaResizer`
- CloudWatch Logs:
  - `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents`

**Environment variables (optional)**
- `UPLOAD_FUNCTION_NAME`: name of Lambda that uploads to S3
- `INSERT_FUNCTION_NAME`: name of Lambda that inserts DB row
- `RESIZE_FUNCTION_NAME`: name of Lambda that resizes the image

### LambdaDeletePhoto
**IAM role permissions**
- `lambda:InvokeFunction` on:
  - `LambdaDeleteObject`
  - `LambdaDeletePhotoDB`

**Environment variables**
- `DELETE_OBJECT_FUNCTION_NAME`: name of Lambda that deletes S3 object
- `DELETE_DB_FUNCTION_NAME`: name of Lambda that deletes DB record

### LambdaInsertPhoto / LambdaDeletePhotoDB / LambdaGetPhotosDB
**IAM role permissions**
- `rds-db:connect` for the target DB user / RDS resource
- (Optional) `rds:DescribeDBInstances` for debugging
- CloudWatch Logs permissions

### S3-access Lambdas (Upload/Get/Delete)
Lambdas that access S3 directly (e.g., `LambdaUploadObject`, `LambdaGetObject`, `LambdaDeleteObject`) typically need:
- `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`
- scoped to the buckets used (`public-mg2`, `resizebucket-vy`)

---

## Notes
- For browser calls to Lambda Function URLs, configure CORS appropriately.
- If thumbnails and originals have different access patterns, keeping them in separate buckets helps isolate policies and optimize retrieval.