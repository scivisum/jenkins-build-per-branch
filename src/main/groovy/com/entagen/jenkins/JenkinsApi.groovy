package com.entagen.jenkins

import groovyx.net.http.ContentType
import groovyx.net.http.HTTPBuilder
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import org.apache.http.conn.HttpHostConnectException
import org.apache.http.client.HttpResponseException
import org.apache.http.HttpStatus
import org.apache.http.protocol.HttpContext
import org.apache.http.HttpRequest

class JenkinsApi {
    String jenkinsServerUrl
    RESTClient restClient
    String jenkinsAuthStr = ""
    boolean findCrumb = true
    def crumbInfo

    public void setJenkinsServerUrl(String jenkinsServerUrl) {
        if (!jenkinsServerUrl.endsWith("/")) jenkinsServerUrl += "/"
        this.jenkinsServerUrl = jenkinsServerUrl
        this.restClient = new RESTClient(jenkinsServerUrl)
    }

    public void addBasicAuth(String jenkinsServerUser, String jenkinsServerPassword) {
        println "use basic authentication"

        def auth = jenkinsServerUser + ':' + jenkinsServerPassword
        jenkinsAuthStr = 'Basic ' + auth.bytes.encodeBase64().toString()
    }

    List<String> getJobNames(String prefix = null) {
        println "getting project names from " + jenkinsServerUrl + "api/json"
        def response = get(path: 'api/json')
        def jobNames = response.data.jobs.name
        if (prefix) return jobNames.findAll { it.startsWith(prefix) }
        return jobNames
    }

    String getJobConfig(String jobName) {
        def response = get(path: "job/${jobName}/config.xml", contentType: TEXT,
                headers: [Accept: 'application/xml'])
        response.data.text
    }

    boolean cloneJobForBranch(ConcreteJob missingJob, List<TemplateJob> templateJobs) {
        String missingJobConfig = configForMissingJob(missingJob, templateJobs)
        TemplateJob templateJob = missingJob.templateJob

        //Copy job with jenkins copy job api, this will make sure jenkins plugins get the call to make a copy if needed (promoted builds plugin needs this)
        post('createItem', missingJobConfig, [name: missingJob.jobName, mode: 'copy', from: templateJob.jobName], ContentType.XML)

        post('job/' + missingJob.jobName + "/config.xml", missingJobConfig, [:], ContentType.XML)
        //Forced disable enable to work around Jenkins' automatic disabling of clones jobs
        //But only if the original job was enabled
        post('job/' + missingJob.jobName + '/disable')
        def enabled = !missingJobConfig.contains("<disabled>true</disabled>")
        if (enabled) {
            post('job/' + missingJob.jobName + '/enable')
        }
        return enabled
    }

    void startJob(ConcreteJob job) {
        println "Starting job ${job.jobName}."
        Map body = [delay: "0sec", Submit: "Build", json: '{"parameter": {"name": "FLUSH_BUILD"}}']
        post('job/' + job.jobName + '/build', body)
    }

    String configForMissingJob(ConcreteJob missingJob, List<TemplateJob> templateJobs) {
        TemplateJob templateJob = missingJob.templateJob
        String config = getJobConfig(templateJob.jobName)

        def ignoreTags = ["assignedNode"]

        // should work if there's a remote ("origin/master") or no remote (just "master")
        config = config.replaceAll("(\\p{Alnum}*[>/])(${templateJob.templateBranchName})<") { fullMatch, prefix, branchName ->
            // jenkins job configs may have certain fields whose values should not be replaced, the most common being <assignedNode>
            // which is used to assign a job to a specific node (potentially "master") and the "master" branch
            if (ignoreTags.find { it + ">" == prefix}) {
                return fullMatch
            } else {
                return "$prefix${missingJob.branchName}<"
            }
        }

        // our template jobs are now set to ignore post commit hooks:
        // re-enable them for branches that are not managed by pipelines
        if (!["develop", "master", "loadtesting_pre-production", "loadtesting_master"].contains(missingJob.branchName)) {
            config = config.replaceAll("<ignorePostCommitHooks>true</ignorePostCommitHooks>", "<ignorePostCommitHooks>false</ignorePostCommitHooks>")
        }

        // this is in case there are other down-stream jobs that this job calls, we want to be sure we're replacing their names as well
        templateJobs.each {
            config = config.replaceAll(it.jobName, it.jobNameForBranch(missingJob.branchName))
        }

        return config
    }

    void deleteJob(String jobName) {
        println "deleting job $jobName"
        post("job/${jobName}/doDelete")
    }

    void createViewForBranch(BranchView branchView, String nestedWithinView = null, String viewRegex = null) {
        String viewName = branchView.viewName
        Map body = [name: viewName, mode: 'hudson.model.ListView', Submit: 'OK', json: '{"name": "' + viewName + '", "mode": "hudson.model.ListView"}']
        println "creating view - viewName:${viewName}, nestedView:${nestedWithinView}"
        post(buildViewPath("createView", nestedWithinView), body)

        String regex = viewRegex ? viewRegex.replaceAll("master", branchView.safeBranchName) : "${branchView.templateJobPrefix ?: ""}.*${branchView.safeBranchName}"
        body = [useincluderegex: 'on', includeRegex: regex, name: viewName, json: '{"name": "' + viewName + '","useincluderegex": {"includeRegex": "' + regex + '"},' + VIEW_COLUMNS_JSON + '}']
        println "configuring view ${viewName}"
        post(buildViewPath("configSubmit", nestedWithinView, viewName), body)
    }

    List<String> getViewNames(String nestedWithinView = null) {
        String path = buildViewPath("api/json", nestedWithinView)
        println "getting views - nestedWithinView:${nestedWithinView} at path: $path"
        def response = get(path: path, query: [tree: 'views[name,jobs[name]]'])
        response.data?.views?.name
    }

    void deleteView(String viewName, String nestedWithinView = null) {
        println "deleting view - viewName:${viewName}, nestedView:${nestedWithinView}"
        post(buildViewPath("doDelete", nestedWithinView, viewName))
    }

    protected String buildViewPath(String pathSuffix, String... nestedViews) {
        List elems = nestedViews.findAll { it != null }
        String viewPrefix = elems.collect { "view/${it}" }.join('/')

        if (viewPrefix) return "$viewPrefix/$pathSuffix"

        return pathSuffix
    }

    protected get(Map map) {
        // get is destructive to the map, if there's an error we want the values around still
        Map mapCopy = map.clone() as Map
        def response

        assert mapCopy.path != null, "'path' is a required attribute for the GET method"

        try {
            response = restClient.get(map)
        } catch (HttpHostConnectException ex) {
            println "Unable to connect to host: $jenkinsServerUrl"
            throw ex
        } catch (UnknownHostException ex) {
            println "Unknown host: $jenkinsServerUrl"
            throw ex
        } catch (HttpResponseException ex) {
            def message = "Unexpected failure with path $jenkinsServerUrl${mapCopy.path}, HTTP Status Code: ${ex.response?.status}, full map: $mapCopy"
            throw new RuntimeException(message, ex)
        }

        assert response.status < 400
        return response
    }

    /**
     * @author Kelly Robinson
     * from https://github.com/kellyrob99/Jenkins-api-tour/blob/master/src/main/groovy/org/kar/hudson/api/PostRequestSupport.groovy
     */
    protected Integer post(String path, postBody = [:], params = [:], ContentType contentType = ContentType.URLENC) {
        HTTPBuilder http = new HTTPBuilder(jenkinsServerUrl)

        def headers = [:]
        if (!jenkinsAuthStr.empty) {
            headers['Authorization'] = jenkinsAuthStr
        }

        Integer status = HttpStatus.SC_EXPECTATION_FAILED

        http.handler.failure = { resp ->
            def msg = "Unexpected failure on $jenkinsServerUrl$path: ${resp.statusLine} ${resp.status}"
            status = resp.statusLine.statusCode
            throw new RuntimeException(msg)
        }

        http.post(path: path, body: postBody, query: params, headers: headers,
                requestContentType: contentType) { resp ->
            assert resp.statusLine.statusCode < 400
            status = resp.statusLine.statusCode
        }
        return status
    }

    static final String VIEW_COLUMNS_JSON = '''
"columns":[
      {
         "$class":"hudson.views.StatusColumn",
         "stapler-class":"hudson.views.StatusColumn",
         "kind":"hudson.views.StatusColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.WeatherColumn",
         "stapler-class":"hudson.views.WeatherColumn",
         "kind":"hudson.views.WeatherColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.JobColumn",
         "stapler-class":"hudson.views.JobColumn",
         "kind":"hudson.views.JobColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastSuccessColumn",
         "stapler-class":"hudson.views.LastSuccessColumn",
         "kind":"hudson.views.LastSuccessColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastFailureColumn",
         "stapler-class":"hudson.views.LastFailureColumn",
         "kind":"hudson.views.LastFailureColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.LastDurationColumn",
         "stapler-class":"hudson.views.LastDurationColumn",
         "kind":"hudson.views.LastDurationColumn$DescriptorImpl"
      },
      {
         "$class":"hudson.views.BuildButtonColumn",
         "stapler-class":"hudson.views.BuildButtonColumn",
         "kind":"hudson.views.BuildButtonColumn$DescriptorImpl"
      }
   ]
'''

}
