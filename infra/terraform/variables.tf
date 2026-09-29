variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "web_origin" {
  description = "Origin of the Angular app (CloudFront), allowed by bucket CORS for direct uploads"
  type        = string
  default     = "https://openfiling.example.gov"
}

variable "api_domain" {
  description = "Host name of the filing-api ALB (used for the Lambda status callback)"
  type        = string
  default     = "api.openfiling.example.gov"
}

variable "claude_model" {
  type    = string
  default = "claude-opus-5-5"
}

variable "redaction_zip" {
  type    = string
  default = "../../redaction-lambda/build/distributions/openfiling-redaction-lambda.zip"
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "db_subnet_group" {
  type    = string
  default = null
}

variable "db_security_group_ids" {
  type    = list(string)
  default = []
}
