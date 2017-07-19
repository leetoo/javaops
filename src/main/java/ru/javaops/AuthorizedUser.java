package ru.javaops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.javaops.model.User;
import ru.javaops.to.AuthUser;
import ru.javaops.to.UserToExt;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import static java.util.Objects.requireNonNull;

/**
 * GKislin
 */
@Slf4j
public class AuthorizedUser extends org.springframework.security.core.userdetails.User {
    private static final long serialVersionUID = 1L;
    public static final String PRE_AUTHORIZED = "PRE_AUTHORIZED";

    private AuthUser user;

    public AuthorizedUser(User user) {
        super(user.getEmail(), user.getPassword() != null ? user.getPassword() : "dummy", true, true, true, true, user.getRoles());
        this.user = new AuthUser(user);
    }

    public static AuthUser user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object user = auth.getPrincipal();
        return (user instanceof AuthUser) ? (AuthUser) user : null;
    }

    public static boolean isAuthorized() {
        return user() != null;
    }

    public static AuthUser authUser() {
        AuthUser user = user();
        requireNonNull(user, "Требуется авторизация");
        return user;
    }

    public static void setPreAuthorized(UserToExt userToExt, HttpServletRequest request) {
        log.info("setPreAuthorized for '{}', '{}'", userToExt.getEmail(), userToExt.getNameSurname());
        HttpSession session = request.getSession(true);
        session.setAttribute(PRE_AUTHORIZED, userToExt);
    }

    public static UserToExt getPreAuthorized(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            return (UserToExt) session.getAttribute(PRE_AUTHORIZED);
        }
        return null;
    }

    @Override
    public String toString() {
        return user == null ? "noAuth" : user.toString();
    }
}
