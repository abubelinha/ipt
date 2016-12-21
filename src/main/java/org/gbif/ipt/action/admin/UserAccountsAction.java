package org.gbif.ipt.action.admin;

import org.gbif.ipt.action.POSTAction;
import org.gbif.ipt.config.AppConfig;
import org.gbif.ipt.model.User;
import org.gbif.ipt.model.User.Role;
import org.gbif.ipt.service.AlreadyExistingException;
import org.gbif.ipt.service.DeletionNotAllowedException;
import org.gbif.ipt.service.DeletionNotAllowedException.Reason;
import org.gbif.ipt.service.admin.RegistrationManager;
import org.gbif.ipt.service.admin.UserAccountManager;
import org.gbif.ipt.struts2.SimpleTextProvider;
import org.gbif.ipt.validation.UserValidator;

import java.io.IOException;
import java.util.List;

import com.google.inject.Inject;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;

/**
 * The Action responsible for all user input relating to the user accounts in the IPT.
 */
public class UserAccountsAction extends POSTAction {

  // logging
  private static final Logger LOG = Logger.getLogger(UserAccountsAction.class);

  private static final long serialVersionUID = 8892204508303815998L;
  private static final int PASSWORD_LENGTH = 8;

  private final UserAccountManager userManager;
  private final UserValidator validator = new UserValidator();

  private User user;
  private String password2;
  private boolean resetPassword;
  private boolean newUser;
  private List<User> users;

  @Inject
  public UserAccountsAction(SimpleTextProvider textProvider, AppConfig cfg, RegistrationManager registrationManager,
    UserAccountManager userManager) {
    super(textProvider, cfg, registrationManager);
    this.userManager = userManager;
  }

  @Override
  public String delete() {
    if (getCurrentUser().getEmail().equalsIgnoreCase(id)) {
      // cant remove logged in user
      addActionError(getText("admin.user.deleted.current"));
    } else {
      try {
        User removedUser = userManager.delete(id);
        if (removedUser == null) {
          return NOT_FOUND;
        }
        userManager.save();
        addActionMessage(getText("admin.user.deleted"));
        return SUCCESS;
      } catch (DeletionNotAllowedException e) {
        if (Reason.LAST_ADMIN == e.getReason()) {
          addActionError(getText("admin.user.deleted.lastadmin"));
        } else if (Reason.LAST_RESOURCE_MANAGER == e.getReason()) {
          addActionError(getText("admin.user.deleted.lastmanager", new String[] {e.getMessage()}));
        } else if (Reason.IS_RESOURCE_CREATOR == e.getReason()) {
          // TODO i18n
          addActionError("The User cannot be deleted as it is the creator for the following resources: " + e.getMessage()
                         + " Consider downgrading the User's role, as an alternative action.");
        } else {
          addActionError(getText("admin.user.deleted.error"));
        }
      } catch (IOException e) {
        addActionError(getText("admin.user.cantSave") + ": " + e.getMessage());
      }
    }
    return INPUT;
  }

  public String getPassword2() {
    return password2;
  }

  public String getNewUser() {
    return newUser ? "yes" : "no";
  }

  // Getters / Setters follow
  public User getUser() {
    return user;
  }

  public List<User> getUsers() {
    return users;
  }

  public String list() {
    users = userManager.list();
    return SUCCESS;
  }

  @Override
  public void prepare() {
    super.prepare();
    if (id == null) {
      newUser = true;
    } else {
      // modify copy of existing user - otherwise we even change the proper instances when canceling the request or
      // submitting non validating data
      user = userManager.get(id);
    }

    // if no id was submitted we wanted to create a new account
    // if an invalid email was entered, it gets stored in the id field and obviously userManager above cant find a
    // matching user.
    // in that case again provide a new, empty user instance
    if (user == null) {
      // reset id
      id = null;
      // create new user
      user = new User();
      newUser = true;
    } else {
      try {
        user = (User) user.clone();
      } catch (CloneNotSupportedException e) {
        LOG.error("An exception occurred while retrieving user: " + e.getMessage(), e);
      }
    }
  }

  @Override
  public String save() {
    try {
      if (id == null) {
        userManager.create(user);
        addActionMessage(getText("admin.user.added"));
      } else if (resetPassword) {
        String newPassword =
          RandomStringUtils.random(PASSWORD_LENGTH, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890");
        user.setPassword(newPassword);
        userManager.save(user);
        addActionMessage(getText("admin.user.passwordChanged", new String[] {user.getEmail(), newPassword}));
      } else {
        if (userManager.get(user.getEmail()).getRole() == Role.Admin && user.getRole() != Role.Admin
          && userManager.list(Role.Admin).size() < 2) {
          addActionError(getText("admin.user.changed.current"));
          return INPUT;
        }
        if (user.getEmail().equals(getCurrentUser().getEmail())) {
          getCurrentUser().setRole(user.getRole());
        }
        userManager.save(user);
        if (getCurrentUser().getRole() != Role.Admin) {
          return HOME;
        }
        addActionMessage(getText("admin.user.changed"));
      }
      return SUCCESS;
    } catch (IOException e) {
      LOG.error("The user change couldnt be saved: " + e.getMessage(), e);
      addActionError(getText("admin.user.saveError"));
      addActionError(e.getMessage());
      return INPUT;
    } catch (AlreadyExistingException e) {
      addActionError(getText("admin.user.exists", new String[] {user.getEmail()}));
      // resetting user
      user = new User();
      return INPUT;
    }
  }

  public void setPassword2(String password2) {
    this.password2 = password2;
  }

  public void setResetPassword(String pass) {
    this.resetPassword = StringUtils.trimToNull(pass) != null;
  }

  public void setUser(User user) {
    this.user = user;
  }

  public void setUsers(List<User> users) {
    this.users = users;
  }

  @Override
  public void validateHttpPostOnly() {
    // only validate on form submit ignoring list views
    // && users == null
    validator.validate(this, user);
    // check 2nd password
    if (newUser && StringUtils.trimToNull(user.getPassword()) != null && !user.getPassword().equals(password2)) {
      addFieldError("password2", getText("validation.password2.wrong"));
      password2 = null;
      user.setPassword(null);
    }

  }
}
