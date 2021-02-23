package app;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeInfo;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.GetChangeRequest;
import com.amazonaws.services.route53.model.InvalidArgumentException;
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;

public class Route53AcmeHelper {

  private static final Logger log = LoggerFactory.getLogger(Route53AcmeHelper.class);

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(System.getProperty("port", "80"));
    String zoneId = System.getProperty("r53zone");
    String authPath = System.getProperty("auth", "auth.properties");
    if (zoneId == null)
      throw new IllegalArgumentException("r53zone");
    AmazonRoute53 r53 = AmazonRoute53Client.builder().build();
    Map<String, List<String>> allowedDomainsByToken = readAuth(authPath);

    Server server = new Server(port);
    server.setHandler(new AbstractHandler() {
      @Override
      public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        try {
          String domain = request.getHeader("CERTBOT-DOMAIN");
          String validation = request.getHeader("CERTBOT-VALIDATION");
          if (domain == null || validation == null)
            throw new IllegalArgumentException("CERTBOT headers");

          assertAuthorized(request, allowedDomainsByToken, domain);

          String challengeDomain = "_acme-challenge." + domain + ".";
          if ("POST".equals(request.getMethod())) {
            addTxtRecords(r53, zoneId, challengeDomain, validation);
            baseRequest.setHandled(true);
          }
          if ("DELETE".equals(request.getMethod())) {
            removeDnsRecord(r53, zoneId, challengeDomain, validation);
            baseRequest.setHandled(true);
          }
        }
        catch (Exception e) {
          throw new ServletException(e);
        }
      }
    });
    server.start();
    server.join();
  }

  private static Map<String, List<String>> readAuth(String authPath) throws Exception {
    // format:
    // bearerToken=comma-separated-domains
    try (Reader r = Files.newBufferedReader(Path.of(authPath))) {
      Properties p = new Properties();
      p.load(r);
      return p.entrySet().stream().collect(Collectors.toMap(
          kv -> (String) kv.getKey(),
          kv -> Arrays.asList(((String) kv.getValue()).split(","))));
    }
  }

  private static void assertAuthorized(HttpServletRequest request, Map<String, List<String>> auth, String domain) {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer "))
      throw new InvalidArgumentException("Authorization");
    String token = authHeader.substring("Bearer ".length());
    List<String> allowedDomains = auth.get(token);
    if (allowedDomains == null || !allowedDomains.contains(domain))
      throw new InvalidArgumentException("Authorization");
  }

  private static synchronized void addTxtRecords(AmazonRoute53 r53, String zoneId, String name, String value) throws Exception {
    value = quote(value);
    ResourceRecordSet rrs = findExisting(r53, zoneId, name);
    if (rrs == null)
      rrs = new ResourceRecordSet(name, RRType.TXT).withTTL(5L);

    log.info("+ {} IN TXT {}", name, value);
    if (rrs.getResourceRecords().size() == 0) {
      applyAndWait(r53, zoneId, name, List.of(
          new Change(ChangeAction.CREATE, addValue(rrs, value))
      ));
    }
    else {
      applyAndWait(r53, zoneId, name, List.of(
          new Change(ChangeAction.DELETE, rrs),
          new Change(ChangeAction.CREATE, addValue(clone(rrs), value))
      ));
    }
  }

  private static synchronized void removeDnsRecord(AmazonRoute53 r53, String zoneId, String name, String value) throws Exception {
    value = quote(value);
    ResourceRecordSet rrs = findExisting(r53, zoneId, name);
    if (rrs == null || !containsValue(rrs, value))
      return;

    log.info("- {} IN TXT {}", name, value);
    if (rrs.getResourceRecords().size() == 1) {
      applyAndWait(r53, zoneId, name, List.of(
          new Change(ChangeAction.DELETE, rrs)
      ));
    }
    else {
      applyAndWait(r53, zoneId, name, List.of(
          new Change(ChangeAction.DELETE, rrs),
          new Change(ChangeAction.CREATE, removeValue(clone(rrs), value))
      ));
    }
  }

  private static boolean containsValue(ResourceRecordSet rrs, String value) {
    return rrs.getResourceRecords().stream().anyMatch(r -> value.equals(r.getValue()));
  }

  private static ResourceRecordSet addValue(ResourceRecordSet rrs, String value) {
    rrs.getResourceRecords().add(new ResourceRecord(value));
    return rrs;
  }

  private static ResourceRecordSet removeValue(ResourceRecordSet rrs, String value) {
    rrs.getResourceRecords().remove(new ResourceRecord(value));
    return rrs;
  }

  private static ResourceRecordSet clone(ResourceRecordSet rrs) {
    return new ResourceRecordSet(rrs.getName(), rrs.getType())
        .withTTL(rrs.getTTL())
        .withResourceRecords(rrs.getResourceRecords());
  }

  private static String quote(String value) {
    return '"' + value + '"';
  }

  private static ResourceRecordSet findExisting(AmazonRoute53 r53, String zoneId, String name) {
    ListResourceRecordSetsRequest req = new ListResourceRecordSetsRequest(zoneId)
        .withStartRecordName(name)
        .withStartRecordType("TXT")
        .withMaxItems("1");
    List<ResourceRecordSet> result = r53.listResourceRecordSets(req).getResourceRecordSets();
    if (result.isEmpty())
      return null;
    ResourceRecordSet candidate = result.get(0);
    if (candidate.getName().equals(name) && candidate.getType().equals("TXT"))
      return candidate;
    return null;
  }

  private static void applyAndWait(AmazonRoute53 r53, String zoneId, String name, List<Change> changes) throws InterruptedException {
    ChangeResourceRecordSetsRequest req = new ChangeResourceRecordSetsRequest(zoneId, new ChangeBatch(changes));
    ChangeInfo pendingChange = r53.changeResourceRecordSets(req).getChangeInfo();
    ChangeInfo progress = null;
    for (int i = 0; i < 30; i++) {
      Thread.sleep(3000);
      progress = r53.getChange(new GetChangeRequest(pendingChange.getId())).getChangeInfo();
      if (!"PENDING".equals(progress.getStatus()))
        break;
    }
    if (!"INSYNC".equals(progress.getStatus()))
      throw new IllegalStateException(name + " failed to sync: " + progress.toString());
  }
}
