package org.jenkinsci.plugins.sonarqubeslackpusher;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;


public class SonarQubeSlackPusher extends Notifier {

   private String hook;
   private String sonarCubeUrl;
   private String jobName;
   private String resolvedJobName; // Needed to avoid getting overwritten when reloading job configuration
   private String branchName;
   private String resolvedBranchName; // Needed to avoid getting overwritten when reloading job configuration
   private String otherChannel;
   private String resolvedChannel; // Needed to avoid getting overwritten when reloading job configuration
   private String username;
   private String password;

   private PrintStream logger = null;

   // Notification contents
   private String id;
   private Attachment attachment = null;

   @DataBoundConstructor
   public SonarQubeSlackPusher(String hook, String sonarQubeUrl, String jobName, String branchName, String otherChannel, String username, String password) {
      this.hook = hook.trim();
      this.sonarCubeUrl = urlFormatting(sonarQubeUrl.trim());
      this.jobName = jobName.trim();
      this.branchName = branchName.trim();
      this.otherChannel = otherChannel.trim();
      this.username = username;
      this.password = password;
   }

   public String getHook() {
      return hook;
   }

   public String getSonarQubeUrl() {
      return sonarCubeUrl;
   }

   public String getJobName() {
      return jobName;
   }

   public String getBranchName() {
      return branchName;
   }

   public String getOtherChannel() {
      return otherChannel;
   }

   public String getUsername() {
      return username;
   }

   public String getPassword() {
      return password;
   }

   @Override
   public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
      // Clean up
      attachment = null;
      logger = listener.getLogger();
      resolvedJobName = parameterReplacement(jobName, build, listener);
      resolvedBranchName = parameterReplacement(branchName, build, listener);
      resolvedChannel =  parameterReplacement(otherChannel, build, listener);
      try {
         getAllNotifications(getSonarQubeData());
      } catch (Exception e) {
         return false;
      }
      pushNotification();
      return true;
   }

   private String urlFormatting(String url) {
      String formatted = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
      if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
         formatted = "http://"+formatted;
      }
      return formatted;
   }

   private String parameterReplacement(String str, AbstractBuild<?, ?> build, BuildListener listener) {
      try {
         EnvVars env = build.getEnvironment(listener);
         env.overrideAll(build.getBuildVariables());
         ArrayList<String> params = getParams(str);
         // TODO
         // This part will override the last found parameter, ie ${a}${b} will only replace and return b.
         for (String param : params) {
            if (env.containsKey(param)) {
               str = env.get(param);
            } else if (build.getBuildVariables().containsKey(param)) {
               str = build.getBuildVariables().get(param);
            } else {
               str = null;
            }
         }
      } catch (InterruptedException ie) {
      } catch (IOException ioe) {
      } finally {
         return str;
      }
   }

   private ArrayList<String> getParams(String str) {
      ArrayList<String> params = new ArrayList<String>();
      final String start = java.util.regex.Pattern.quote("${");
      final String end = "}";

      String[] rawParams = str.split(start);
      for (int i = 1; i < rawParams.length; i++) {
         if (rawParams[i].contains(end)) {
            String[] raw = rawParams[i].split(java.util.regex.Pattern.quote(end));
            if (raw.length > 0) {
               params.add(raw[0]);
            }
         }
      }
      return params;
   }

   @Extension
   public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {

      public DescriptorImpl() {
         load();
      }

      @Override
      public String getDisplayName() {
         return "SonarQube Slack pusher";
      }

      @Override
      public boolean isApplicable(Class<? extends AbstractProject> jobType) {
         return true;
      }

      public FormValidation doCheckHook(@QueryParameter String value)
         throws IOException, ServletException {
         String url = value;
         if ((url == null) || url.equals("")) {
            return FormValidation.error("Please specify a valid URL");
         } else {
            try {
               new URL(url);
               return FormValidation.ok();
            } catch (Exception e) {
               return FormValidation.error("Please specify a valid URL.");
            }
         }
      }

      public FormValidation doCheckSonarUrl(@QueryParameter String value)
         throws IOException, ServletException {
         String url = value;
         if ((url == null) || url.equals("")) {
            return FormValidation.error("Please specify a valid URL");
         } else {
            try {
               new URL(url);
               return FormValidation.ok();
            } catch (Exception e) {
               return FormValidation.error("Please specify a valid URL.");
            }
         }
      }

      public FormValidation doCheckJobName(@QueryParameter String value)
         throws IOException, ServletException {
         String name = value;
         if ((name == null) || name.equals("")) {
            return FormValidation.error("Please enter a SonarQube job name.");
         }
         return FormValidation.ok();
      }
   }

   public BuildStepMonitor getRequiredMonitorService() {
      return BuildStepMonitor.NONE;
   }

   private String getSonarQubeData() throws Exception {
      String path = "/api/resources?metrics=alert_status,quality_gate_details&includealerts=true";
      CloseableHttpClient client = HttpClientBuilder.create().build();
      HttpGet get = new HttpGet(sonarCubeUrl + path);

      if (username != null || !username.isEmpty()) {
         String encoding = new Base64().encodeAsString(new String(username + ":" + password).getBytes());
         get.setHeader("Authorization", "Basic " + encoding);
      }

      CloseableHttpResponse res;
      try {
         logger.println("[ssp] Calling SonarQube on: " + sonarCubeUrl + path);
         res = client.execute(get);
         if (res.getStatusLine().getStatusCode() != 200) {
            logger.println("[ssp] Got a non 200 response from SonarQube. Server responded with '" + res.getStatusLine().getStatusCode() + " : " + res.getStatusLine
               ().getReasonPhrase() + "'");
            throw new Exception("Got a non 200 status code from SonarQube!");
         }
         return EntityUtils.toString(res.getEntity());
      } catch(ClientProtocolException cpe) {
         logger.println("[ssp] Could not get SonarQube results, ClientProtocolException, exception: '" + cpe.getMessage() + "'");
         throw cpe;
      } catch(IOException ioe) {
         logger.println("[ssp] Could not get SonarQube results, IOException, exception: '" + ioe.getMessage() + "'");
         throw ioe;
      } catch(Exception e) {
         logger.println("[ssp] Could not get SonarQube results, exception: '" + e.getMessage() + "'");
         throw e;
      } finally {
         client.close();
      }
   }

   private void getAllNotifications(String data) {
      JSONParser jsonParser = new JSONParser();
      JSONArray jobs = null;
      try {
         jobs = (JSONArray)jsonParser.parse(data);
      } catch (ParseException pe) {
         logger.println("[ssp] Could not parse the response from SonarQube '" + data + "'");
         return;
      }

      String name = resolveJobName();
      for (Object job : jobs) {
         if (((JSONObject) job).get("name").toString().equals(name)) {
            id = ((JSONObject) job).get("id").toString();
            JSONArray msrs = (JSONArray) ((JSONObject) job).get("msr");
            for (Object msr : msrs) {
               if (((JSONObject) msr).get("key").equals("alert_status")) {
                  if (((JSONObject) msr).get("alert") != null) {
                     String alert = ((JSONObject) msr).get("alert").toString();
                     if (alert.equalsIgnoreCase("ERROR") || alert.equalsIgnoreCase("WARN")) {
                        attachment = new Attachment();
                        attachment.setAlert(alert);
                        attachment.setAlertText(((JSONObject) msr).get("alert_text").toString());
                     }
                  }
               }
            }
         }
      }
   }

   private String resolveJobName() {
      String name = jobName;
      if (resolvedJobName != null && !resolvedJobName.equals("")) {
         name = resolvedJobName;
      }
      if (resolvedBranchName != null && !resolvedBranchName.equals("")) {
         name += " " + resolvedBranchName;
         name.trim();
      }
      else if (branchName != null && !branchName.equals("")) {
         name += " " + branchName;
         name.trim();
      }
      return name;
   }

   private String getResolvedChannel() {
      if (resolvedChannel == null || resolvedChannel.equals("")) {
         return "default";
      } else {
         return resolvedChannel;
      }
   }

   private String pushNotificationContent() {
      if (attachment == null) {
         String msg = "[ssp] No failed quality checks found for project '";
         msg += resolveJobName();
         msg += "' nothing to report to the '"+getResolvedChannel()+"' Slack channel.";
         logger.println(msg);
         return null;
      }
      String linkUrl = null;
      try {
         linkUrl = new URI(sonarCubeUrl + "/dashboard/index/" + id).normalize().toString();
      } catch (URISyntaxException use) {
         logger.println("[ssp] Could not create link to SonarQube job with the following content'" + sonarCubeUrl + "/dashboard/index/" + id + "'");
      }
      String message = "{";
      if (resolvedChannel != null) {
         message += "\"channel\":\"" + resolvedChannel + "\",";
      }
      message += "\"username\":\"SonarQube Slack Pusher\",";
      message += "\"text\":\"<" + linkUrl + "|*SonarQube job*>\\n" +
         "*Job:* " + resolvedJobName;
      if (resolvedBranchName != null) {
         message += "\\n*Branch:* " + resolvedBranchName;
      }
      message += "\",\"attachments\":[";
      message += attachment.getAttachment();
      message += "]}";
      return message;
   }

   private void pushNotification() {
      String message = pushNotificationContent();
      if (message == null) {
         return;
      }
      HttpPost post = new HttpPost(hook);
      HttpEntity entity = new StringEntity(message, "UTF-8");
      post.addHeader("Content-Type", "application/json");
      post.setEntity(entity);
      HttpClient client = HttpClientBuilder.create().build();
      logger.println("[ssp] Pushing notification(s) to the '"+getResolvedChannel()+"' Slack channel.");
      try {
         HttpResponse res = client.execute(post);
         if (res.getStatusLine().getStatusCode() != 200) {
            logger.println("[ssp] Could not push to Slack... got a non 200 response. Post body: '" + message + "'");
         }
      } catch (IOException ioe) {
         logger.println("[ssp] Could not push to slack... got an exception: '" + ioe.getMessage() + "'");
      }
   }
}
