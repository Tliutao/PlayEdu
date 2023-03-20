package xyz.playedu.api.controller.backend;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import xyz.playedu.api.PlayEduBCtx;
import xyz.playedu.api.constant.BPermissionConstant;
import xyz.playedu.api.domain.*;
import xyz.playedu.api.event.CourseDestroyEvent;
import xyz.playedu.api.exception.NotFoundException;
import xyz.playedu.api.middleware.BackendPermissionMiddleware;
import xyz.playedu.api.request.backend.CourseRequest;
import xyz.playedu.api.service.CourseChapterService;
import xyz.playedu.api.service.CourseHourService;
import xyz.playedu.api.service.CourseService;
import xyz.playedu.api.service.ResourceCategoryService;
import xyz.playedu.api.types.JsonResponse;
import xyz.playedu.api.types.paginate.CoursePaginateFiler;
import xyz.playedu.api.types.paginate.PaginationResult;

import java.text.ParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Author 杭州白书科技有限公司
 * @create 2023/2/24 14:16
 */
@RestController
@Slf4j
@RequestMapping("/backend/v1/course")
public class CourseController {

    @Autowired
    private CourseService courseService;

    @Autowired
    private ResourceCategoryService categoryService;

    @Autowired
    private CourseChapterService chapterService;

    @Autowired
    private CourseHourService hourService;

    @Autowired
    private ApplicationContext ctx;

    @GetMapping("/index")
    public JsonResponse index(@RequestParam HashMap<String, Object> params) {
        Integer page = MapUtils.getInteger(params, "page", 1);
        Integer size = MapUtils.getInteger(params, "size", 10);
        String sortField = MapUtils.getString(params, "sort_field");
        String sortAlgo = MapUtils.getString(params, "sort_algo");

        String title = MapUtils.getString(params, "title");
        String depIds = MapUtils.getString(params, "dep_ids");
        String categoryIds = MapUtils.getString(params, "category_ids");
        Integer isRequired = MapUtils.getInteger(params, "is_requried");

        CoursePaginateFiler filter = new CoursePaginateFiler();
        filter.setTitle(title);
        filter.setSortField(sortField);
        filter.setSortAlgo(sortAlgo);
        filter.setCategoryIds(categoryIds);
        filter.setDepIds(depIds);
        filter.setIsRequired(isRequired);

        PaginationResult<Course> result = courseService.paginate(page, size, filter);

        HashMap<String, Object> data = new HashMap<>();
        data.put("data", result.getData());
        data.put("total", result.getTotal());
        data.put("category_count", courseService.getCategoryCount());
        data.put("pure_total", courseService.total());

        return JsonResponse.data(data);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.COURSE)
    @GetMapping("/create")
    public JsonResponse create() {
        HashMap<String, Object> data = new HashMap<>();
        data.put("categories", categoryService.groupByParent());
        return JsonResponse.data(data);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.COURSE)
    @PostMapping("/create")
    @Transactional
    public JsonResponse store(@RequestBody @Validated CourseRequest req) throws ParseException {
        Course course = courseService.createWithCategoryIdsAndDepIds(
                req.getTitle(),
                req.getThumb(),
                req.getIsRequired(),
                req.getIsShow(),
                req.getCategoryIds(),
                req.getDepIds()
        );

        Date now = new Date();

        if (req.getHours().size() > 0) {//无章节课时配置
            List<CourseHour> insertHours = new ArrayList<>();
            final Integer[] chapterSort = {0};
            for (CourseRequest.HourItem hourItem : req.getHours()) {
                insertHours.add(new CourseHour() {{
                    setCourseId(course.getId());
                    setChapterId(0);
                    setSort(chapterSort[0]++);
                    setTitle(hourItem.getName());
                    setType(hourItem.getType());
                    setDuration(hourItem.getDuration());
                    setRid(hourItem.getRid());
                    setCreatedAt(now);
                }});
            }
            if (insertHours.size() > 0) {
                hourService.saveBatch(insertHours);
            }
        } else {
            if (req.getChapters() == null || req.getChapters().size() == 0) {
                return JsonResponse.error("请配置课时");
            }

            List<CourseHour> insertHours = new ArrayList<>();
            final Integer[] chapterSort = {0};

            for (CourseRequest.ChapterItem chapterItem : req.getChapters()) {
                CourseChapter tmpChapter = new CourseChapter() {{
                    setCourseId(course.getId());
                    setSort(chapterSort[0]++);
                    setName(chapterItem.getName());
                    setCreatedAt(now);
                    setUpdatedAt(now);
                }};

                chapterService.save(tmpChapter);

                final Integer[] hourSort = {0};
                for (CourseRequest.HourItem hourItem : chapterItem.getHours()) {
                    insertHours.add(new CourseHour() {{
                        setChapterId(tmpChapter.getId());
                        setCourseId(course.getId());
                        setSort(hourSort[0]++);
                        setTitle(hourItem.getName());
                        setType(hourItem.getType());
                        setDuration(hourItem.getDuration());
                        setRid(hourItem.getRid());
                        setCreatedAt(now);
                    }});
                }
            }
            if (insertHours.size() > 0) {
                hourService.saveBatch(insertHours);
            }
        }
        return JsonResponse.success();
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.COURSE)
    @GetMapping("/{id}")
    public JsonResponse edit(@PathVariable(name = "id") Integer id) throws NotFoundException {
        Course course = courseService.findOrFail(id);
        List<Integer> depIds = courseService.getDepIdsByCourseId(course.getId());
        List<Integer> categoryIds = courseService.getCategoryIdsByCourseId(course.getId());
        List<CourseChapter> chapters = chapterService.getChaptersByCourseId(course.getId());
        List<CourseHour> hours = hourService.getHoursByCourseId(course.getId());

        HashMap<String, Object> data = new HashMap<>();
        data.put("course", course);
        data.put("dep_ids", depIds);//已关联的部门
        data.put("category_ids", categoryIds);//已关联的分类
        data.put("chapters", chapters);
        data.put("hours", hours.stream().collect(Collectors.groupingBy(CourseHour::getChapterId)));

        return JsonResponse.data(data);
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.COURSE)
    @PutMapping("/{id}")
    @Transactional
    public JsonResponse update(@PathVariable(name = "id") Integer id, @RequestBody @Validated CourseRequest req) throws NotFoundException {
        Course course = courseService.findOrFail(id);
        courseService.updateWithCategoryIdsAndDepIds(course, req.getTitle(), req.getThumb(), req.getIsRequired(), req.getIsShow(), req.getCategoryIds(), req.getDepIds());
        return JsonResponse.success();
    }

    @BackendPermissionMiddleware(slug = BPermissionConstant.COURSE)
    @DeleteMapping("/{id}")
    public JsonResponse destroy(@PathVariable(name = "id") Integer id) {
        courseService.removeById(id);
        ctx.publishEvent(new CourseDestroyEvent(this, PlayEduBCtx.getAdminUserID(), id));
        return JsonResponse.success();
    }

}
