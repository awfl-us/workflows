variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "Default region for resources"
  type        = string
  default     = "us-central1"
}

variable "github_repository" {
  description = "GitHub repository in the form org/repo (used to restrict OIDC provider)"
  type        = string
}

variable "wif_pool_id" {
  description = "Workload Identity Pool ID (unique per repo)"
  type        = string
}

variable "wif_provider_id" {
  description = "Workload Identity Provider ID (unique per repo)"
  type        = string
}

variable "create_deploy_sa" {
  description = "Whether to create the deployment service account"
  type        = bool
  default     = true
}

variable "deploy_sa_name" {
  description = "Account ID for the deployer SA (used when create_deploy_sa=true). Must be <=30 chars, lowercase"
  type        = string
  default     = "workflows-deployer"
}

variable "deploy_sa_display_name" {
  description = "Display name for the deployer SA"
  type        = string
  default     = "Workflows Deployer"
}

variable "deploy_sa_email" {
  description = "Email of an existing deployer SA (used when create_deploy_sa=false)"
  type        = string
  default     = ""
}
