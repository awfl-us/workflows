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

# -----------------------------------------------------------------------------------
# Allow the deploy SA to ActAs the runtime service account used by Workflows
# Since our workflows do not specify serviceAccount, GCP defaults to the Compute
# Engine default SA: {PROJECT_NUMBER}-compute@developer.gserviceaccount.com
# Workflows creation requires iam.serviceAccounts.actAs on that SA.
# -----------------------------------------------------------------------------------

data "google_project" "current" {
  project_id = var.project_id
}

locals {
  compute_default_sa_email = "${data.google_project.current.number}-compute@developer.gserviceaccount.com"
}

# Grant ActAs on the default compute SA to the deployer (one-time per project)
resource "google_service_account_iam_member" "deploy_actas_compute_default" {
  count              = local.deploy_sa_member != null ? 1 : 0
  service_account_id = "projects/${var.project_id}/serviceAccounts/${local.compute_default_sa_email}"
  role               = "roles/iam.serviceAccountUser"
  member             = local.deploy_sa_member
}
