# Deployment Service Account and IAM bindings for Workflows deployment

locals {
  deploy_sa_email  = var.create_deploy_sa ? google_service_account.deploy[0].email : var.deploy_sa_email
  deploy_sa_member = local.deploy_sa_email != "" ? "serviceAccount:${local.deploy_sa_email}" : null
}

# Optionally create the deployment service account
resource "google_service_account" "deploy" {
  count        = var.create_deploy_sa ? 1 : 0
  account_id   = var.deploy_sa_name
  display_name = var.deploy_sa_display_name
}

# Grant Workflows Admin to the deploy SA (created case)
resource "google_project_iam_member" "deploy_workflows_admin_created" {
  count   = var.create_deploy_sa ? 1 : 0
  project = var.project_id
  role    = "roles/workflows.admin"
  member  = "serviceAccount:${google_service_account.deploy[0].email}"
}

# Grant Workflows Admin to the deploy SA (existing case)
resource "google_project_iam_member" "deploy_workflows_admin_existing" {
  count   = (!var.create_deploy_sa && var.deploy_sa_email != "") ? 1 : 0
  project = var.project_id
  role    = "roles/workflows.admin"
  member  = "serviceAccount:${var.deploy_sa_email}"
}
