/**
 * This file is part of the PlayEdu.
 * (c) 杭州白书科技有限公司
 */
package xyz.playedu.api.controller.backend;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import xyz.playedu.api.constant.BPermissionConstant;
import xyz.playedu.api.constant.SystemConstant;
import xyz.playedu.api.domain.Department;
import xyz.playedu.api.domain.User;
import xyz.playedu.api.domain.UserDepartment;
import xyz.playedu.api.event.UserDestroyEvent;
import xyz.playedu.api.exception.NotFoundException;
import xyz.playedu.api.middleware.BackendPermissionMiddleware;
import xyz.playedu.api.request.backend.UserImportRequest;
import xyz.playedu.api.request.backend.UserRequest;
import xyz.playedu.api.service.DepartmentService;
import xyz.playedu.api.service.UserService;
import xyz.playedu.api.service.internal.UserDepartmentService;
import xyz.playedu.api.types.JsonResponse;
import xyz.playedu.api.types.paginate.PaginationResult;
import xyz.playedu.api.types.paginate.UserPaginateFilter;
import xyz.playedu.api.util.HelperUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author 杭州白书科技有限公司
 *
 * @create 2023/2/23 09:48
 */
@RestController
@Slf4j
@RequestMapping("/backend/v1/user")
public class UserController {

    @Autowired private UserService userService;

    @Autowired private UserDepartmentService userDepartmentService;

    @Autowired private DepartmentService departmentService;

    @Autowired private ApplicationContext context;

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_INDEX)
    @GetMapping("/index")
    public JsonResponse index(@RequestParam HashMap<String, Object> params) {
        Integer page = MapUtils.getInteger(params, "page", 1);
        Integer size = MapUtils.getInteger(params, "size", 10);
        String sortField = MapUtils.getString(params, "sort_field");
        String sortAlgo = MapUtils.getString(params, "sort_algo");

        String name = MapUtils.getString(params, "name");
        String email = MapUtils.getString(params, "email");
        String idCard = MapUtils.getString(params, "id_card");
        Integer isActive = MapUtils.getInteger(params, "is_active");
        Integer isLock = MapUtils.getInteger(params, "is_lock");
        Integer isVerify = MapUtils.getInteger(params, "is_verify");
        Integer isSetPassword = MapUtils.getInteger(params, "is_set_password");
        String createdAt = MapUtils.getString(params, "created_at");
        String depIds = MapUtils.getString(params, "dep_ids");

        UserPaginateFilter filter =
                new UserPaginateFilter() {
                    {
                        setName(name);
                        setEmail(email);
                        setIdCard(idCard);
                        setIsActive(isActive);
                        setIsLock(isLock);
                        setIsVerify(isVerify);
                        setIsSetPassword(isSetPassword);
                        setDepIds(depIds);
                        setSortAlgo(sortAlgo);
                        setSortField(sortField);
                    }
                };

        if (createdAt != null && createdAt.trim().length() > 0) {
            filter.setCreatedAt(createdAt.split(","));
        }

        PaginationResult<User> result = userService.paginate(page, size, filter);

        HashMap<String, Object> data = new HashMap<>();
        data.put("data", result.getData());
        data.put("total", result.getTotal());
        data.put(
                "user_dep_ids",
                userService.getDepIdsGroup(result.getData().stream().map(User::getId).toList()));
        data.put("departments", departmentService.id2name());

        return JsonResponse.data(data);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_STORE)
    @GetMapping("/create")
    public JsonResponse create() {
        return JsonResponse.data(null);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_STORE)
    @PostMapping("/create")
    public JsonResponse store(@RequestBody @Validated UserRequest req) {
        String email = req.getEmail();
        if (userService.emailIsExists(email)) {
            return JsonResponse.error("邮箱已存在");
        }
        String password = req.getPassword();
        if (password.length() == 0) {
            return JsonResponse.error("请输入密码");
        }
        userService.createWithDepIds(
                email,
                req.getName(),
                req.getAvatar(),
                req.getPassword(),
                req.getIdCard(),
                req.getDepIds());
        return JsonResponse.success();
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_UPDATE)
    @GetMapping("/{id}")
    public JsonResponse edit(@PathVariable(name = "id") Integer id) throws NotFoundException {
        User user = userService.findOrFail(id);

        List<Integer> depIds = userService.getDepIdsByUserId(user.getId());

        HashMap<String, Object> data = new HashMap<>();
        data.put("user", user);
        data.put("dep_ids", depIds);

        return JsonResponse.data(data);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_UPDATE)
    @PutMapping("/{id}")
    @Transactional
    public JsonResponse update(
            @PathVariable(name = "id") Integer id, @RequestBody @Validated UserRequest req)
            throws NotFoundException {
        User user = userService.findOrFail(id);

        String email = req.getEmail();
        if (!email.equals(user.getEmail()) && userService.emailIsExists(email)) {
            return JsonResponse.error("邮箱已存在");
        }

        userService.updateWithDepIds(
                user,
                email,
                req.getName(),
                req.getAvatar(),
                req.getPassword(),
                req.getIdCard(),
                req.getDepIds());
        return JsonResponse.success();
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.USER_DESTROY)
    @DeleteMapping("/{id}")
    public JsonResponse destroy(@PathVariable(name = "id") Integer id) throws NotFoundException {
        User user = userService.findOrFail(id);
        userService.removeById(user.getId());
        context.publishEvent(new UserDestroyEvent(this, user.getId()));
        return JsonResponse.success();
    }

    @PostMapping("/store-batch")
    @Transactional
    public JsonResponse batchStore(@RequestBody @Validated UserImportRequest req) {
        List<UserImportRequest.UserItem> users = req.getUsers();
        if (users.size() == 0) {
            return JsonResponse.error("数据为空");
        }
        if (users.size() > 1000) {
            return JsonResponse.error("一次最多导入1000条数据");
        }

        Integer startLine = req.getStartLine();

        List<String[]> errorLines = new ArrayList<>();
        errorLines.add(new String[] {"错误行", "错误信息"}); // 错误表-表头

        // 读取存在的部门
        List<Department> departments = departmentService.all();
        Map<Integer, String> depId2Name =
                departments.stream()
                        .collect(Collectors.toMap(Department::getId, Department::getName));
        HashMap<String, Integer> depChainNameMap = new HashMap<>();
        for (Department tmpDepItem : departments) {
            // 一级部门
            if (tmpDepItem.getParentChain() == null || tmpDepItem.getParentChain().length() == 0) {
                depChainNameMap.put(tmpDepItem.getName(), tmpDepItem.getId());
                continue;
            }

            // 多级部门
            String[] tmpChainIds = tmpDepItem.getParentChain().split(",");
            List<String> tmpChainNames = new ArrayList<>();
            for (int i = 0; i < tmpChainIds.length; i++) {
                String tmpName = depId2Name.get(Integer.valueOf(tmpChainIds[i]));
                if (tmpName == null) {
                    continue;
                }
                tmpChainNames.add(tmpName);
            }
            tmpChainNames.add(tmpDepItem.getName());
            depChainNameMap.put(String.join("-", tmpChainNames), tmpDepItem.getId());
        }

        // 邮箱输入重复检测 || 部门存在检测
        HashMap<String, Integer> emailRepeat = new HashMap<>();
        HashMap<String, Integer[]> depMap = new HashMap<>();
        List<String> emails = new ArrayList<>();
        List<User> insertUsers = new ArrayList<>();
        int i = -1;

        for (UserImportRequest.UserItem userItem : users) {
            i++; // 索引值

            if (userItem.getEmail() == null || userItem.getEmail().trim().length() == 0) {
                errorLines.add(new String[] {"第" + (i + startLine) + "行", "未输入邮箱账号"});
            } else {
                // 邮箱重复判断
                Integer repeatLine = emailRepeat.get(userItem.getEmail());
                if (repeatLine != null) {
                    errorLines.add(
                            new String[] {
                                "第" + (i + startLine) + "行", "与第" + repeatLine + "行邮箱重复"
                            });
                } else {
                    emailRepeat.put(userItem.getEmail(), i + startLine);
                }
                emails.add(userItem.getEmail());
            }

            // 部门数据检测
            if (userItem.getDeps() == null || userItem.getDeps().trim().length() == 0) {
                errorLines.add(new String[] {"第" + (i + startLine) + "行", "未选择部门"});
            } else {
                String[] tmpDepList = userItem.getDeps().trim().split("\\|");
                Integer[] tmpDepIds = new Integer[tmpDepList.length];
                for (int j = 0; j < tmpDepList.length; j++) {
                    // 获取部门id
                    Integer tmpDepId = depChainNameMap.get(tmpDepList[j]);
                    // 判断部门id是否存在
                    if (tmpDepId == null || tmpDepId == 0) {
                        errorLines.add(
                                new String[] {
                                    "第" + (i + startLine) + "行", "部门『" + tmpDepList[j] + "』不存在"
                                });
                        continue;
                    }
                    tmpDepIds[j] = tmpDepId;
                }
                depMap.put(userItem.getEmail(), tmpDepIds);
            }

            // 姓名为空检测
            String tmpName = userItem.getName();
            if (tmpName == null || tmpName.trim().length() == 0) {
                errorLines.add(new String[] {"第" + (i + startLine) + "行", "昵称为空"});
            }

            // 密码为空检测
            String tmpPassword = userItem.getPassword();
            if (tmpPassword == null || tmpPassword.trim().length() == 0) {
                errorLines.add(new String[] {"第" + (i + startLine) + "行", "密码为空"});
            }

            // 待插入数据
            User tmpInsertUser = new User();
            String tmpSalt = HelperUtil.randomString(6);
            tmpInsertUser.setEmail(userItem.getEmail());
            tmpInsertUser.setPassword(HelperUtil.MD5(tmpPassword + tmpSalt));
            tmpInsertUser.setSalt(tmpSalt);
            tmpInsertUser.setName(tmpName);
            tmpInsertUser.setIdCard(userItem.getIdCard());
            tmpInsertUser.setCreateIp(SystemConstant.INTERNAL_IP);
            tmpInsertUser.setCreateCity(SystemConstant.INTERNAL_IP_AREA);
            tmpInsertUser.setCreatedAt(new Date());
            tmpInsertUser.setUpdatedAt(new Date());

            insertUsers.add(tmpInsertUser);
        }

        if (errorLines.size() > 1) {
            return JsonResponse.error("导入数据有误", errorLines);
        }

        // 邮箱是否注册检测
        List<String> existsEmails = userService.existsEmailsByEmails(emails);
        if (existsEmails.size() > 0) {
            for (String tmpEmail : existsEmails) {
                errorLines.add(new String[] {"第" + emailRepeat.get(tmpEmail) + "行", "邮箱已注册"});
            }
        }
        if (errorLines.size() > 1) {
            return JsonResponse.error("导入数据有误", errorLines);
        }

        userService.saveBatch(insertUsers);

        // 部门关联
        List<UserDepartment> insertUserDepartments = new ArrayList<>();
        for (User tmpUser : insertUsers) {
            Integer[] tmpDepIds = depMap.get(tmpUser.getEmail());
            if (tmpDepIds == null) {
                continue;
            }
            for (Integer tmpDepId : tmpDepIds) {
                insertUserDepartments.add(
                        new UserDepartment() {
                            {
                                setUserId(tmpUser.getId());
                                setDepId(tmpDepId);
                            }
                        });
            }
        }
        userDepartmentService.saveBatch(insertUserDepartments);

        return JsonResponse.success();
    }
}
