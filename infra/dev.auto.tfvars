# Development defaults for Terraform variables
# Copy/edit as needed. Terraform will auto-load any *.auto.tfvars files.

project_id           = "awfl-us"
region               = "us-central1"

# GitHub repository in the form org/repo; used to restrict the WIF provider
github_repository    = "awfl-us/workflows"

# Use unique IDs per repo/environment to avoid collisions
wif_pool_id          = "github-worklfows-pool"
wif_provider_id      = "github-workflows"

# Service Account options
create_deploy_sa       = true
deploy_sa_name         = "workflows-deployer"
deploy_sa_display_name = "Workflows Deployer (dev)"
# If create_deploy_sa = false, specify the existing SA email below; otherwise leave ""
deploy_sa_email        = ""
