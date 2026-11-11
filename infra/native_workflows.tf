# Compiled Rust workflows use a separate, private GCS bucket. This is
# intentionally owned by the SAME Terraform state as the Workflows deployer
# and existing workflow file buckets, not by cloud environment storage.
resource "google_storage_bucket" "native_artifacts" {
  name                        = lower("${var.project_id}-native-workflows")
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = false

  versioning {
    enabled = true
  }

  labels = {
    app       = "awfl-workflows"
    component = "native-rust"
  }
}

# GCP IAM is the only deployment authority. Only the existing Workflows
# deployment service account may publish binaries and switch active releases.
resource "google_storage_bucket_iam_member" "native_artifact_deployer" {
  count  = local.deploy_sa_member != null ? 1 : 0
  bucket = google_storage_bucket.native_artifacts.name
  role   = "roles/storage.objectAdmin"
  # Reference the GCP-managed deployer identity without introducing a
  # Terraform dependency on refreshing its unrelated deletion_policy.
  member = "serviceAccount:${var.create_deploy_sa ? "${var.deploy_sa_name}@${var.project_id}.iam.gserviceaccount.com" : var.deploy_sa_email}"
}

# Match existing Workflows publication permissions: the gcloud storage
# client may read bucket metadata as well as create GCS objects.
resource "google_storage_bucket_iam_member" "native_artifact_deployer_metadata" {
  count  = local.deploy_sa_member != null ? 1 : 0
  bucket = google_storage_bucket.native_artifacts.name
  role   = "roles/storage.legacyBucketReader"
  member = "serviceAccount:${var.create_deploy_sa ? "${var.deploy_sa_name}@${var.project_id}.iam.gserviceaccount.com" : var.deploy_sa_email}"
}

# API and Jobs currently use the default Compute runtime service account.
# It may read approved releases but cannot overwrite or deploy them.
resource "google_storage_bucket_iam_member" "native_artifact_runtime_reader" {
  bucket = google_storage_bucket.native_artifacts.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${local.compute_default_sa_email}"
}

output "native_artifact_bucket" {
  description = "Dedicated GCS bucket for compiled AWFL Rust workflows"
  value       = google_storage_bucket.native_artifacts.name
}
