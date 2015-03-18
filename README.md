# sonar-slack-pusher
Jenkins plugin for pushing Sonar quality gate statuses to a given Slack channel.

The plugin runs as a post build action and runs no matter the outcome of the job. The Sonar jobs needs to have a quality gate defined and liked to the project against whom the job is evaluated against.

### Configuration

Parameter | Usage | Examples
--------------- | -------------------------- | --------
Slack hook|This is the hook into a given channel and it is generated by the Slack incoming Webhook extension. Just paste the full URL here.|https://hooks.slack.com/services/T2341HS4D/B041W83EG/jpzllllC9ugOn8YYaf7s2hV
Sonar root URL|This is the root of the remote Sonar installation. The URL is the base for the metrics query and linkage to jobs.|sonar.mycompany.com:9000
Sonar job name|The name the project has in Sonar, usually project name followed by the branch name, '<job name> <branch'.|'super-awesome-service bugfixBranch'

### Supported metrics

The following metrics are supported in the notification.

* qi-quality-index
* coverage
* test_success_density
* blocker_violations
* critical_violations