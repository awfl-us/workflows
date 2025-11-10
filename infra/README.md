Infrastructure (Terraform)

This Terraform config creates:
- A deployment service account for Google Cloud Workflows (optional).
- A Workload Identity Federation (WIF) pool and provider restricted to a single GitHub repository.
- A .github/actions-variables.json file containing values to set as GitHub repository variables.

Files
- versions.tf: Providers and versions.
- variables.tf: Input variables.
- iam.tf: Deployment service account and Workflows IAM bindings.
- wif.tf: WIF pool/provider and impersonation bindings; outputs the GitHub vars file.
- outputs.tf: Useful outputs.

Inputs (examples)
- project_id: your GCP project id
- region: us-central1
- github_repository: org/repo
- wif_pool_id: github-web-pool
- wif_provider_id: github-web
- create_deploy_sa: true
- deploy_sa_name: workflows-deployer
- deploy_sa_display_name: Workflows Deployer
- deploy_sa_email: leave empty when creating the SA; otherwise set to existing SA email

Usage
1) Initialize and plan
   terraform -chdir=infra init
   terraform -chdir=infra plan \
     -var "project_id=your-project" \
     -var "region=us-central1" \
     -var "github_repository=org/repo" \
     -var "wif_pool_id=github-web-pool" \
     -var "wif_provider_id=github-web"

2) Apply
   terraform -chdir=infra apply \
     -var "project_id=your-project" \
     -var "region=us-central1" \
     -var "github_repository=org/repo" \
     -var "wif_pool_id=github-web-pool" \
     -var "wif_provider_id=github-web"

3) After apply, set GitHub repository variables using the generated file:
   gh variable set -f .github/actions-variables.json

Notes
- Each repo should have its own unique wif_pool_id and wif_provider_id to avoid collisions.
- The generated keys align with the GitHub Actions workflow in .github/workflows/deploy-workflows.yml:
  - GCP_WORKLOAD_IDENTITY_PROVIDER
  - GCP_SERVICE_ACCOUNT
  - GCP_PROJECT_ID
  - GCP_REGION
- Ensure the Workflows API is enabled in the project.
