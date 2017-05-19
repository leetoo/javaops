package ru.javaops.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.javaops.model.*;
import ru.javaops.service.GroupService;
import ru.javaops.service.MailService;
import ru.javaops.service.SubscriptionService;
import ru.javaops.service.UserService;
import ru.javaops.to.UserTo;

import javax.validation.Valid;

import static org.springframework.web.bind.annotation.RequestMethod.DELETE;
import static org.springframework.web.bind.annotation.RequestMethod.POST;

/**
 * GKislin
 */

@RestController
@RequestMapping(value = "/api/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserRestController {

    @Autowired
    private UserService userService;

    @Autowired
    private MailService mailService;

    @Autowired
    private GroupService groupService;

    @RequestMapping(method = DELETE)
    public void delete(@RequestParam("email") String email) {
        userService.deleteByEmail(email);
    }

    @RequestMapping(value = "/pay", method = POST)
    public String pay(@RequestParam("group") String group, @Valid UserTo userTo,
                      @RequestParam("sum") int sum, @RequestParam("currency") Currency currency, @RequestParam("comment") String comment,
                      @RequestParam(value = "type", required = false) ParticipationType participationType,
                      @RequestParam(value = "channel", required = false) String channel,
                      @RequestParam(value = "template", required = false) String template) {
        UserGroup ug = groupService.pay(userTo, group, new Payment(sum, currency, comment), participationType, channel);
        if (SubscriptionService.isRef(ug.getChannel())) {
            String refEmail = ug.getChannel().substring(1);
            User refUser = userService.findByEmail(refEmail);
            refUser.addBonus(15);
            userService.save(refUser);
            // TODO send paid email
        }
        return ug.toString() + '\n' + (template == null ? "No template" : mailService.sendToUser(template, ug.getUser()));
    }
}