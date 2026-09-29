# OpenFiling — AWS reference infrastructure (documentation only; never applied by this repo).
# Maps the local components 1:1:
#   s3-emulator buckets      -> aws_s3_bucket.documents / aws_s3_bucket.public_copies
#   ObjectCreated -> :9001   -> aws_s3_bucket_notification.redaction -> aws_lambda_function.redaction
#   filing-api               -> ECS Fargate service (task role below) behind an ALB (see comments)
#   PostgreSQL :5433         -> aws_db_instance.filing (RDS PostgreSQL 18, Multi-AZ in prod)
terraform {
  required_version = ">= 1.9"
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}

provider "aws" {
  region = var.region
}

locals {
  name = "openfiling-${var.environment}"
  tags = { Project = "openfiling", Environment = var.environment }
}

# ---------------------------------------------------------------- encryption
resource "aws_kms_key" "data" {
  description             = "${local.name} documents, public copies and RDS"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  tags                    = local.tags
}

# ---------------------------------------------------------------- buckets
resource "aws_s3_bucket" "documents" {
  bucket = "${local.name}-documents"
  tags   = local.tags
}

resource "aws_s3_bucket" "public_copies" {
  bucket = "${local.name}-public-copies"
  tags   = local.tags
}

resource "aws_s3_bucket_public_access_block" "all" {
  for_each                = { documents = aws_s3_bucket.documents.id, public = aws_s3_bucket.public_copies.id }
  bucket                  = each.value
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "all" {
  for_each = { documents = aws_s3_bucket.documents.id, public = aws_s3_bucket.public_copies.id }
  bucket   = each.value
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.data.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_versioning" "documents" {
  bucket = aws_s3_bucket.documents.id
  versioning_configuration { status = "Enabled" }
}

# Unfinished uploads never linger (the API also expires PENDING_UPLOAD rows).
resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"
    filter {}
    abort_incomplete_multipart_upload { days_after_initiation = 1 }
  }
}

# Browser uploads straight to S3 with the pre-signed PUT URL from filing-api.
resource "aws_s3_bucket_cors_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id
  cors_rule {
    allowed_methods = ["PUT", "GET", "HEAD"]
    allowed_origins = [var.web_origin]
    allowed_headers = ["*"]
    expose_headers  = ["ETag", "x-amz-request-id"]
    max_age_seconds = 3600
  }
}

resource "aws_s3_bucket_cors_configuration" "public_copies" {
  bucket = aws_s3_bucket.public_copies.id
  cors_rule {
    allowed_methods = ["GET", "HEAD"]
    allowed_origins = [var.web_origin]
    allowed_headers = ["*"]
    max_age_seconds = 3600
  }
}

# TLS only.
data "aws_iam_policy_document" "tls_only" {
  for_each = { documents = aws_s3_bucket.documents.arn, public = aws_s3_bucket.public_copies.arn }
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    resources = [each.value, "${each.value}/*"]
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "tls_only" {
  for_each = { documents = aws_s3_bucket.documents.id, public = aws_s3_bucket.public_copies.id }
  bucket   = each.value
  policy   = data.aws_iam_policy_document.tls_only[each.key].json
}

# ---------------------------------------------------------------- redaction Lambda
resource "aws_secretsmanager_secret" "anthropic" {
  name       = "${local.name}/anthropic-api-key"
  kms_key_id = aws_kms_key.data.arn
  tags       = local.tags
}

resource "aws_secretsmanager_secret" "internal_token" {
  name       = "${local.name}/internal-token"
  kms_key_id = aws_kms_key.data.arn
  tags       = local.tags
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "redaction" {
  name               = "${local.name}-redaction"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = local.tags
}

# Least privilege: read only public originals, write only public copies.
data "aws_iam_policy_document" "redaction" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.documents.arn}/public/*"]
  }
  statement {
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.public_copies.arn}/public/*"]
  }
  statement {
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.data.arn]
  }
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.anthropic.arn, aws_secretsmanager_secret.internal_token.arn]
  }
  statement {
    actions   = ["logs:CreateLogGroup", "logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["arn:aws:logs:*:*:*"]
  }
}

resource "aws_iam_role_policy" "redaction" {
  role   = aws_iam_role.redaction.id
  policy = data.aws_iam_policy_document.redaction.json
}

resource "aws_lambda_function" "redaction" {
  function_name = "openfiling-redaction"
  role          = aws_iam_role.redaction.arn
  runtime       = "java21"
  handler       = "gov.openfiling.redaction.RedactionHandler::handleRequest"
  filename      = var.redaction_zip # redaction-lambda/build/distributions/*.zip (.\gradlew.bat lambdaZip)
  memory_size   = 3008              # PDF rasterization is CPU/memory bound
  timeout       = 900
  architectures = ["arm64"]
  snap_start { apply_on = "PublishedVersions" }
  reserved_concurrent_executions = 20 # protects the Claude rate limit and the API callback

  environment {
    variables = {
      DOCUMENTS_BUCKET      = aws_s3_bucket.documents.bucket
      PUBLIC_BUCKET         = aws_s3_bucket.public_copies.bucket
      CLAUDE_MODEL          = var.claude_model
      CLAUDE_EFFORT         = "medium"
      CALLBACK_URL          = "https://${var.api_domain}/internal/v1/redactions/events"
      # Follow-up before a real deployment: the handler currently reads ANTHROPIC_API_KEY / INTERNAL_TOKEN
      # from env; resolve these ARNs at init (AWS Parameters and Secrets Lambda Extension) instead.
      ANTHROPIC_SECRET_ARN  = aws_secretsmanager_secret.anthropic.arn
      INTERNAL_TOKEN_SECRET = aws_secretsmanager_secret.internal_token.arn
      RENDER_DPI            = "150"
    }
  }
  tracing_config { mode = "Active" }
  tags = local.tags
}

resource "aws_lambda_permission" "s3" {
  statement_id  = "AllowS3Invoke"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.redaction.function_name
  principal     = "s3.amazonaws.com"
  source_arn    = aws_s3_bucket.documents.arn
}

# The heart of objective 3: a public original landing in S3 triggers the redaction function.
resource "aws_s3_bucket_notification" "redaction" {
  bucket = aws_s3_bucket.documents.id
  lambda_function {
    id                  = "redaction"
    lambda_function_arn = aws_lambda_function.redaction.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "public/"
    filter_suffix       = ".pdf"
  }
  # Locally the emulator also notifies filing-api (/internal/v1/storage/events). In AWS use an
  # EventBridge rule -> SQS -> API worker for that path (not shown).
  depends_on = [aws_lambda_permission.s3]
}

# ---------------------------------------------------------------- filing-api task role (ECS Fargate)
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "filing_api" {
  name               = "${local.name}-filing-api"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = local.tags
}

# Pre-signed URLs carry the signer's permissions, so the task role is what the browser can do.
data "aws_iam_policy_document" "filing_api" {
  statement {
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = ["${aws_s3_bucket.documents.arn}/*"]
  }
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.public_copies.arn}/*"]
  }
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.documents.arn, aws_s3_bucket.public_copies.arn]
  }
  statement {
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.data.arn]
  }
  statement {
    actions   = ["lambda:InvokeFunction"]
    resources = [aws_lambda_function.redaction.arn]
  }
}

resource "aws_iam_role_policy" "filing_api" {
  role   = aws_iam_role.filing_api.id
  policy = data.aws_iam_policy_document.filing_api.json
}

# ---------------------------------------------------------------- RDS PostgreSQL
resource "aws_db_instance" "filing" {
  identifier                   = "${local.name}-db"
  engine                       = "postgres"
  engine_version               = "18"
  instance_class               = var.db_instance_class
  allocated_storage            = 50
  max_allocated_storage        = 500
  db_name                      = "openfiling"
  username                     = "openfiling"
  manage_master_user_password  = true # credentials in Secrets Manager with rotation
  storage_encrypted            = true
  kms_key_id                   = aws_kms_key.data.arn
  multi_az                     = var.environment == "prod"
  backup_retention_period      = 14
  deletion_protection          = var.environment == "prod"
  performance_insights_enabled = true
  db_subnet_group_name         = var.db_subnet_group
  vpc_security_group_ids       = var.db_security_group_ids
  skip_final_snapshot          = var.environment != "prod"
  tags                         = local.tags
}

output "documents_bucket" { value = aws_s3_bucket.documents.bucket }
output "public_copies_bucket" { value = aws_s3_bucket.public_copies.bucket }
output "redaction_function" { value = aws_lambda_function.redaction.arn }
output "db_endpoint" { value = aws_db_instance.filing.endpoint }
