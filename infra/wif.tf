# Workload Identity Federation (WIF) for GitHub Actions OIDC (repo-scoped)
# Creates a dedicated WIF pool/provider restricted to this repository and
# grants impersonation on the deployment service account.

data "google_project" "wif_project" {
  project_id = var.project_id
}

# Dedicated pool for this repo (avoid clashing with other repos' pools)
resource "google_iam_workload_identity_pool" "github_repo" {
  project                   = var.project_id
  workload_identity_pool_id = var.wif_pool_id
  display_name              = "GitHub OIDC (${var.github_repository})"
  description               = "Trust GitHub Actions OIDC tokens for ${var.github_repository}"
}

# OIDC provider for GitHub within the above pool, restricted to this repository
resource "google_iam_workload_identity_pool_provider" "github_repo" {
  project                            = var.project_id
  workload_identity_pool_id          = google_iam_workload_identity_pool.github_repo.workload_identity_pool_id
  workload_identity_pool_provider_id = var.wif_provider_id
  display_name                       = "GitHub (${var.github_repository})"
  description                        = "OIDC provider for GitHub Actions (${var.github_repository})"

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }

  # Restrict to this repository
  attribute_condition = "assertion.repository == '${var.github_repository}'"
}

# Allow identities from the GitHub OIDC provider (this repository) to impersonate the deploy SA
# Case 1: we create the deploy SA in this repo
resource "google_service_account_iam_member" "deploy_wif_impersonation_created" {
  count              = var.create_deploy_sa ? 1 : 0
  service_account_id = google_service_account.deploy[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.wif_project.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github_repo.workload_identity_pool_id}/attribute.repository/${var.github_repository}"
}

# Case 2: deploy SA already exists (create_deploy_sa=false) — bind WIF to provided email
resource "google_service_account_iam_member" "deploy_wif_impersonation_existing" {
  count              = (!var.create_deploy_sa && var.deploy_sa_email != "") ? 1 : 0
  service_account_id = "projects/${var.project_id}/serviceAccounts/${var.deploy_sa_email}"
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/projects/${data.google_project.wif_project.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.github_repo.workload_identity_pool_id}/attribute.repository/${var.github_repository}"
}

# Generate GitHub Actions repository variables file to be applied via workflow
resource "local_file" "actions_variables" {
  filename = "${path.module}/../.github/actions-variables.json"
  content  = jsonencode({
    # These keys align with the GitHub Actions workflow expectations
    GCP_WORKLOAD_IDENTITY_PROVIDER = google_iam_workload_identity_pool_provider.github_repo.name
    GCP_SERVICE_ACCOUNT            = var.create_deploy_sa ? google_service_account.deploy[0].email : (var.deploy_sa_email != "" ? var.deploy_sa_email : null)
    GCP_PROJECT_ID                 = var.project_id
    GCP_REGION                     = var.region
  })
  depends_on = [
    google_iam_workload_identity_pool_provider.github_repo,
  ]
}
