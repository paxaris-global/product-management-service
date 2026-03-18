
import os
import subprocess
import tempfile
import requests
import argparse
import os
import subprocess
import tempfile
import requests
import argparse

def create_argocd_app(app_name, repo_url, path, namespace="argocd", dest_namespace="default"):
    # Ensure app_name is RFC 1123 compliant
    safe_app_name = app_name.replace('_', '-').lower()

    app_yaml = f"""
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: {safe_app_name}
  namespace: {namespace}
spec:
  project: default
  source:
    repoURL: '{repo_url}'
    targetRevision: main
    path: {path}
  destination:
    server: 'https://kubernetes.default.svc'
    namespace: {dest_namespace}
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
"""

    yaml_file = os.path.join(tempfile.gettempdir(), f"{safe_app_name}-argocd-app.yaml")

    with open(yaml_file, "w") as f:
        f.write(app_yaml)

    try:
        subprocess.run(["kubectl", "apply", "-f", yaml_file], check=True)
        print(f"ArgoCD Application '{safe_app_name}' created/updated successfully.")
    except subprocess.CalledProcessError as e:
        print(f"Error applying ArgoCD Application for {safe_app_name}: {e}")
    finally:
        if os.path.exists(yaml_file):
            os.remove(yaml_file)


def fetch_org_repos(org, token):
    headers = {"Authorization": f"token {token}"}
    repos = []
    url = f"https://api.github.com/orgs/{org}/repos?per_page=100"

    while url:
        resp = requests.get(url, headers=headers)
        if resp.status_code != 200:
            print(f"Failed to fetch org repos for {org}: {resp.text}")
            return []

        repos.extend(resp.json())
        url = resp.links['next']['url'] if 'next' in resp.links else None

    return repos


def fetch_personal_repos(token):
    headers = {"Authorization": f"token {token}"}
    repos = []
    url = "https://api.github.com/user/repos?per_page=100"

    while url:
        resp = requests.get(url, headers=headers)
        if resp.status_code != 200:
            print(f"Failed to fetch personal repos: {resp.text}")
            return []

        repos.extend(resp.json())
        url = resp.links['next']['url'] if 'next' in resp.links else None

    return repos
