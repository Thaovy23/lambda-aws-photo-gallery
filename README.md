## Serverless Photo Gallery on AWS (LambdaAWS)

This project is a **serverless photo gallery** built on AWS.  
It showcases an end-to-end architecture using **AWS Lambda (Java), Lambda Function URLs, S3, RDS MySQL, Systems Manager Parameter Store and token-based authentication** to provide secure photo uploads, thumbnail generation and a simple web UI.

### What the app does

- **Login with email-based token**
  - `LambdaGenerateToken` generates a token from the user’s email and a secret key in Parameter Store (HMAC-SHA256).
  - `LambdaVerifyToken` validates the token for every protected operation.
- **Upload photos with description**
  - Frontend (`index.html`) converts the image to Base64 and sends it plus a description to `LambdaUploadWithDescription`.
  - The Lambda hashes the filename to create a stable S3 key, verifies the token, then:
    - uploads the original image to S3 bucket `public-mg2` (`LambdaUploadObject`),
    - creates a resized thumbnail and stores it in `resizebucket-vy` (`LambdaResizer`),
    - inserts metadata (S3 key, description, email) into an RDS MySQL `Photos` table (`LambdaInsertPhoto`).
- **View bucket-like gallery**
  - `LambdaGetPhotosDB` reads from the `Photos` table and returns a list of photos.
  - The web UI shows a table with thumbnails, description, owner email, download and delete actions.
- **Download and delete**
  - `LambdaGetObject` returns the image bytes (original or resized) for display/download.
  - `LambdaDeletePhoto` orchestrates deletion in S3 (`LambdaDeleteObject`) and RDS (`LambdaDeletePhotoDB`) in parallel.

From a recruiter’s perspective, this project demonstrates:
- Ability to design and implement an **end-to-end serverless architecture** on AWS.
- Hands-on experience with **Lambda (Java), S3, RDS, IAM, Parameter Store, Function URLs** and browser JavaScript.
- Understanding of **authentication, authorization, secure data handling and orchestration between multiple Lambdas**.

---

### Tech stack

- **Frontend**: Vanilla HTML, CSS, JavaScript (`index.html`).
- **Backend**: AWS Lambda functions written in **Java** (AWS SDK v2).
- **Storage**:
  - **S3**: original images (`public-mg2`) and resized thumbnails (`resizebucket-vy`).
  - **RDS MySQL**: `Photos` table storing S3 key, description, email.
- **Security & config**:
  - **Systems Manager Parameter Store**: secret key for HMAC token (`keytokenhash`).
  - **IAM roles & policies** for Lambda → S3, Lambda → RDS, Lambda → Parameter Store.
- **Triggering**:
  - **Lambda Function URLs** for direct HTTP access from the browser.
  - Optional **LambdaTimer** (CloudWatch / EventBridge) to warm up functions and reduce cold starts.

---

### Architecture overview

High-level flow:

1. User opens the static `index.html` (locally or from S3/CloudFront).
2. User enters email → `LambdaGenerateToken` → receives token.
3. User uploads image + description → `LambdaUploadWithDescription`:
   - verifies token with `LambdaVerifyToken`,
   - uploads original to S3,
   - resizes and uploads thumbnail to a separate bucket,
   - writes metadata to RDS.
4. User clicks **View My Bucket** → `LambdaGetPhotosDB` → table of photos is rendered.
5. Thumbnails are loaded on-demand via `LambdaGetObject`.
6. User can **Download** (original) or **Delete** (original + thumbnail + DB record) through `LambdaDeletePhoto`.

The diagram below summarises the main components and data flow (you can update the image with a nicer version for your CV):

![Architecture](assets/architecture-diagram.png)

You can also add a sequence diagram image here (e.g. `assets/sequence-upload.png`) to show the upload flow in interviews:

![Upload Sequence](assets/sequence-upload.png)

---

### Lambda & IAM permissions (security highlights)

#### LambdaUploadWithDescription

- **IAM Role permissions**:
  - `lambda:InvokeFunction` on:
    - `LambdaUploadObject`
    - `LambdaInsertPhoto`
    - `LambdaResizer`
  - `logs:CreateLogGroup`, `logs:CreateLogStream`, `logs:PutLogEvents` for CloudWatch Logs.
- **Environment variables (optional overrides)**:
  - `UPLOAD_FUNCTION_NAME` = name of Lambda that uploads to S3.
  - `INSERT_FUNCTION_NAME` = name of Lambda that inserts DB row.
  - `RESIZE_FUNCTION_NAME` = name of Lambda that resizes the image.

#### LambdaDeletePhoto

- **IAM Role permissions**:
  - `lambda:InvokeFunction` on:
    - `LambdaDeleteObject`
    - `LambdaDeletePhotoDB`
- **Environment variables**:
  - `DELETE_OBJECT_FUNCTION_NAME` = name of Lambda that deletes S3 object.
  - `DELETE_DB_FUNCTION_NAME` = name of Lambda that deletes row in DB.

#### LambdaInsertPhoto / LambdaDeletePhotoDB / LambdaGetPhotosDB

- **IAM Role permissions**:
  - `rds-db:connect` for the specific RDS instance and DB user (see policy example below).
  - `rds:DescribeDBInstances` (optional, helpful for debugging).
  - CloudWatch Logs permissions.

#### Other IAM notes

- Lambdas that access S3 directly (e.g. `LambdaUploadObject`, `LambdaDeleteObject`, `LambdaGetObject`) need:
  - `s3:PutObject`, `s3:GetObject`, `s3:DeleteObject`
  - on the buckets used (`public-mg2`, `resizebucket-vy`, and any static-website bucket if needed).
- All Lambda roles should have CloudWatch Logs permissions:
  - `logs:CreateLogGroup`
  - `logs:CreateLogStream`
  - `logs:PutLogEvents`
