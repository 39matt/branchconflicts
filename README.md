# Merge base conflicts
## Information
- This is a Kotlin library that helps you find files that were changed in both **branchA (remote)** and **branchB (local)**.

## Usage
- Inside the library there are many functions made for the functionality to work and be clean
- The main function you should be using is **find_conflicts(...)**

### find_conflicts()
- The function accesses GitHub repository from GitHub REST API and finds the branchA
- Goes through the local repository to find the branchB
- Compares the two branches since their merge base and returns the conflicted files
#### Parameters
1) repo_owner - username of GitHub user whose repository you will be finding conflicts in
2) repo_name - name of the repository
3) access_token - if the repository is empty the library needs token for authentication
4) local_repo_path - path to the cloned repository where local branch is located
5) branch_a - remote branch name on GitHub
6) branch_b - local branch name

#### Return value
- A list of strings (names of conflicted files)
