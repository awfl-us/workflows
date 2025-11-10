output "deploy_service_account_email" {
  description = "Email of the deployment service account"
  value       = var.create_deploy_sa ? google_service_account.deploy[0].email : var.deploy_sa_email
}

output "wif_provider_name" {
  description = "Fully-qualified Workload Identity Provider name"
  value       = google_iam_workload_identity_pool_provider.github_repo.name
}

output "wif_pool_id" {
  description = "Workload Identity Pool ID"
  value       = google_iam_workload_identity_pool.github_repo.workload_identity_pool_id
}
