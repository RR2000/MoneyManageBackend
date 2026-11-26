package com.rondinella.moneymanageapi.auth.github;

import com.rondinella.moneymanageapi.auth.AuthService;
import com.rondinella.moneymanageapi.auth.User;
import com.rondinella.moneymanageapi.auth.UserRepository;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * "Accedi con GitHub": the GitHub account is mapped to the local account with one of its verified emails, or to a new
 * empty account named after the GitHub login. Unverified emails are ignored (they could be anybody's), and accounts
 * registered with a password have an email only once its owner confirmed it from a link (EmailLinks), so they are
 * never taken over. The principal's name is the local username.
 */
@Service
public class GitHubUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

  /** One entry of https://api.github.com/user/emails (scope user:email). */
  public record GitHubEmail(String email, boolean primary, boolean verified) {
  }

  private final UserRepository users;
  private final AuthService authService;
  private final OAuth2UserService<OAuth2UserRequest, OAuth2User> gitHubProfile = new DefaultOAuth2UserService();
  private final RestClient api = RestClient.create("https://api.github.com");

  public GitHubUserService(UserRepository users, AuthService authService) {
    this.users = users;
    this.authService = authService;
  }

  @Override
  public OAuth2User loadUser(OAuth2UserRequest request) {
    OAuth2User profile = gitHubProfile.loadUser(request);
    List<GitHubEmail> emails = api.get().uri("/user/emails")
        .header("Authorization", "Bearer " + request.getAccessToken().getTokenValue())
        .header("Accept", "application/vnd.github+json")
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    return toLocalUser(emails == null ? List.of() : emails, Objects.toString(profile.getAttributes().get("login"), ""));
  }

  /** Separate from the HTTP calls, so it can be tested without GitHub. */
  public OAuth2User toLocalUser(List<GitHubEmail> emails, String gitHubLogin) {
    List<String> verified = emails.stream()
        .filter(GitHubEmail::verified)
        .sorted(Comparator.comparing(GitHubEmail::primary).reversed())
        .map(e -> e.email().trim().toLowerCase(Locale.ROOT))
        .toList();
    if (verified.isEmpty()) {
      throw new OAuth2AuthenticationException(new OAuth2Error("no_verified_email",
          "The GitHub account has no verified email.", null));
    }
    User user = users.findFirstByEmailIn(verified)
        .orElseGet(() -> authService.createAccount(freeUsername(gitHubLogin), null, verified.getFirst()));
    return new DefaultOAuth2User(AuthorityUtils.createAuthorityList("ROLE_" + user.getRole()),
        Map.of("username", user.getUsername()), "username");
  }

  /** The GitHub login if free (and valid here), otherwise login-gh, login-gh2... */
  private String freeUsername(String login) {
    String base = login.replaceAll("[^A-Za-z0-9._-]", "");
    if (base.length() < 3) base = "github-" + base;
    if (base.length() > 26) base = base.substring(0, 26);
    String candidate = base;
    for (int i = 1; users.findByUsername(candidate).isPresent(); i++) {
      candidate = base + "-gh" + (i == 1 ? "" : i);
    }
    return candidate;
  }
}
