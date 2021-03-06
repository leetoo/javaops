package ru.javaops.web;

import com.google.common.collect.ImmutableMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import ru.javaops.AuthorizedUser;
import ru.javaops.model.*;
import ru.javaops.service.*;
import ru.javaops.to.AuthUser;
import ru.javaops.to.UserMailImpl;
import ru.javaops.to.UserTo;
import ru.javaops.to.UserToExt;
import ru.javaops.util.ProjectUtil;
import ru.javaops.util.Util;
import ru.javaops.util.WebUtil;
import ru.javaops.util.exception.NotMemberException;

import javax.mail.MessagingException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.ValidationException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * GKislin
 */
@Controller
@Slf4j
public class SubscriptionController {

    @Autowired
    private IntegrationService integrationService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private RefService refService;

    @Autowired
    private MailService mailService;

    @Autowired
    private UserService userService;

    @Autowired
    private GroupService groupService;

    @Autowired
    private AuthService authService;

    @Autowired
    private IdeaCouponService ideaCouponService;

    @Autowired
    private CachedProjects cachedProjects;

    @GetMapping("/activate")
    public ModelAndView activate(@RequestParam("email") String email, @RequestParam("activate") boolean activate, @RequestParam("key") String key) {
        log.info("User {} set activete={}", email, activate);
        User u = userService.findByEmail(email);
        if (u != null && u.isActive() != activate) {
            u.setActive(activate);
            u.setActivatedDate(new Date());
            userService.save(u);
        }
        return new ModelAndView("message/activation",
                ImmutableMap.of("activate", activate,
                        "subscriptionUrl", subscriptionService.getSubscriptionUrl(email, key, !activate)));
    }

    @PostMapping("/register-group")
    public ModelAndView registerInGroup(@RequestParam("group") String group,
                                        @RequestParam(value = "confirmMail", required = false) String confirmMail,
                                        @RequestParam(value = "callback", required = false) String callback,
                                        @RequestParam("channel") String channel,
                                        @RequestParam(value = "template", required = false) String template,
                                        @RequestParam(value = "type", required = false) ParticipationType participationType,
                                        @RequestParam("channelKey") String channelKey,
                                        @Valid UserTo userTo, BindingResult bindResult) {
        if (bindResult.hasErrors()) {
            throw new ValidationException(Util.getErrorMessage(bindResult));
        }
        UserGroup userGroup = groupService.registerAtGroup(userTo, group, channel, participationType);

        String result = "ок";
        if (userGroup.getGroup().getType() == GroupType.FRANCHISE) {
            result = subscriptionService.grantGoogleDrive(userGroup.getUser(), userGroup.getGroup().getProject().getName());
        } else if (!StringUtils.isEmpty(template)) {
            result = mailService.sendWithTemplate(template, userGroup.getUser(), ImmutableMap.of("participationType", participationType == null ? "" : participationType));
        }
        ImmutableMap.Builder<String, Object> builder =
                new ImmutableMap.Builder<String, Object>()
                        .put("userGroup", userGroup)
                        .put("result", result);

        ImmutableMap<String, ?> params = builder.build();

        final ModelAndView mv;
        if (callback != null) {
            mv = getRedirectView(result, callback, "error");
        } else {
            mv = new ModelAndView("simpleConfirm", params);
        }
        if (confirmMail != null) {
            mailService.sendToUserAsync("simpleConfirm", new UserMailImpl(null, confirmMail), params);
        }
        return mv;
    }

    @PostMapping("/register-site")
    public String registerSite(@CookieValue(value = RefController.COOKIE_REF, required = false) String refUserId,
                               @CookieValue(value = RefController.COOKIE_CHANNEL, required = false) String cookieChannel,
                               HttpServletRequest request) {

        UserToExt userToExt = authService.getPreAuthorized(request);
        if (userToExt == null) {
            WebUtil.logWarn(request);
            return null;
        }

        String channel = refService.findChannel(refUserId, cookieChannel, null);
        log.info("+++ !!! Site register, {} from channel {}", userToExt, channel);
        User user = userService.create(userToExt, channel);
        authService.setAuthorized(user, request);
        return "redirect:/auth/profile";
    }

    @PostMapping("/register")
    public ModelAndView registerAtProject(@RequestParam("project") String projectName,
                                          @RequestParam(value = "channel", required = false) String channel,
                                          @RequestParam(value = "template", required = false) String template,
                                          @Valid UserTo userTo, BindingResult result,
                                          @CookieValue(value = RefController.COOKIE_CHANNEL, required = false) String cookieChannel,
                                          @CookieValue(value = RefController.COOKIE_REF, required = false) String refUserId,
                                          HttpServletRequest request, HttpServletResponse response) {
        if (result.hasErrors()) {
            throw new ValidationException(Util.getErrorMessage(result));
        }
        channel = refService.findChannel(refUserId, cookieChannel, channel);
        log.info("+++ !!! Project {} register, {} from channel {}", projectName, userTo, channel);

        UserGroup userGroup = groupService.registerAtProject(userTo, projectName, channel);
        User user = userGroup.getUser();
        if (userGroup.getRegisterType() == RegisterType.DUPLICATED) {
            Date date = userGroup.getRegisteredDate();
            if (date != null) {
                LocalDate ld = LocalDate.of(date.getYear() + 1900, date.getMonth() + 1, date.getDate());
                if (ld.isAfter(LocalDate.now().minus(15, ChronoUnit.DAYS))) {
                    return getRedirectView("/view/message/duplicate");
                }
            }
            userGroup.setRegisteredDate(new Date());
            groupService.saveDirect(userGroup);
        } else if (userGroup.getRegisterType() == RegisterType.REPEAT) {
            integrationService.asyncSendSlackInvitation(user.getEmail(), projectName);
            template = projectName + "/repeat";
        } else {
            if (template == null) {
                template = projectName + "/entrance";
            }
            User refUser = refService.getRefUser(user);
            if (refUser != null) {
                refService.sendAsyncMail(refUser, "ref/refRegistration", ImmutableMap.of("project", projectName, "email", userTo.getEmail()));
            }
        }
        if (userGroup.getRegisterType() == RegisterType.FIRST_REGISTERED) {
            authService.setAuthorized(user, request);
        }
        String mailResult = mailService.sendToUser(template, user);
        return getRedirectView(mailResult, "/view/message/confirm", "/view/message/error");
    }

    private ModelAndView getRedirectView(String mailResult, String successUrl, String failUrl) {
        return getRedirectView(MailService.isOk(mailResult) ? successUrl : failUrl);
    }

    private ModelAndView getRedirectView(String url) {
        return new ModelAndView("util/redirectToUrl", "redirectUrl", url);
    }

    @PostMapping("/auth/repeat")
    public ModelAndView repeat(@RequestParam("email") String email,
                               @RequestParam("project") String projectName) throws MessagingException {

        email = email.toLowerCase();
        User user = userService.findExistedByEmail(email);

        AuthUser authUser = AuthorizedUser.authUser();
        if (authUser.isCurrent(projectName)) {
            return new ModelAndView("message/alreadyRegistered", "project", projectName);
        }
        if (authUser.isFinished(projectName)) {
            ProjectUtil.Props projectProps = groupService.getProjectProps(projectName);
            groupService.saveDirect(new UserGroup(user, projectProps.currentGroup, RegisterType.REPEAT, "repeat", ParticipationType.REGULAR));
            authService.updateAuthParticipation(authUser);
            mailService.sendToUser(projectName + "/repeat", user);
            IntegrationService.SlackResponse response = integrationService.sendSlackInvitation(email, projectName);
            return new ModelAndView("message/registration",
                    ImmutableMap.of("response", response, "email", email, "project", projectName));
        }
        throw new NotMemberException(email, projectName);
    }

    @GetMapping("/idea")
    public ModelAndView ideaRegister(@RequestParam("email") String email, @RequestParam("project") String projectName) throws MessagingException {
        User user = userService.findExistedByEmail(email);
        if (!user.isMember()) {
            throw new NotMemberException(user.getEmail());
        }
        IdeaCoupon coupon = ideaCouponService.assignToUser(user, cachedProjects.getByName(projectName));
        String response = mailService.sendWithTemplate("idea_register", user, ImmutableMap.of("coupon", coupon.getCoupon()));
        if (MailService.OK.equals(response)) {
            return new ModelAndView("message/registrationIDEA");
        } else {
            throw new IllegalStateException("Ошибка отправки почты" + response);
        }
    }
}