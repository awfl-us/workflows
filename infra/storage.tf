// Storage buckets for workflows
// Bucket names include the project id for global uniqueness and are forced to lowercase.

locals {
  workflow_files_bucket     = lower("${var.project_id}-workflow-files")
  workflow_files_dev_bucket = lower("${var.project_id}-workflow-filesdev")
}

resource "google_storage_bucket" "workflow_scripts" {
  name     = local.workflow_files_bucket
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  force_destroy               = false
  labels = {
    app = "awfl-workflows"
    env = "prod"
  }
}

resource "google_storage_bucket" "workflow_scripts_dev" {
  name     = local.workflow_files_dev_bucket
  location = var.region

  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  force_destroy               = false
  labels = {
    app = "awfl-workflows"
    env = "dev"
  }
}

# -----------------------------------------------------------------------------
# IAM: Allow Workflows runtime service account to read objects in the files buckets
# By default, Workflows uses the Compute Engine default SA:
#   ${data.google_project.current.number}-compute@developer.gserviceaccount.com
# iam.tf defines data.google_project.current and local.compute_default_sa_email;
# locals are module-scoped, so we reference local.compute_default_sa_email here.
# -----------------------------------------------------------------------------
resource "google_storage_bucket_iam_member" "workflow_files_viewer" {
  for_each = toset([
    google_storage_bucket.workflow_scripts.name,
    google_storage_bucket.workflow_scripts_dev.name,
  ])
  bucket = each.value
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${local.compute_default_sa_email}"
}
