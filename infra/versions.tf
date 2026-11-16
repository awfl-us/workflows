terraform {
  required_version = ">= 1.5.0"
  required_providers {
    google = {
      source  = "hashicorp/google"
      # Match the existing server provider major and avoid silently upgrading
      # state to Google provider v8 when publishing the native-artifact bucket.
      version = ">= 7.10.0, < 8.0.0"
    }
    local = {
      source  = "hashicorp/local"
      version = ">= 2.4"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
