INSERT INTO rubric_template (id, subject_id, criteria_name, description, created_at, updated_at)
SELECT v.id, NULL, v.criteria_name, v.description, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM (
    SELECT 'c0a80101-0000-4000-8000-000000000001' AS id,
           'Hoàn thành & Chất lượng' AS criteria_name,
           'Làm đúng, đủ task được giao; code/chức năng chạy ổn định, ít lỗi.' AS description
    UNION ALL
    SELECT 'c0a80101-0000-4000-8000-000000000002',
           'Tiến độ & Quy trình',
           'Đáp ứng đúng deadline; đẩy/merge code kịp thời, không làm kẹt tiến độ chung.'
    UNION ALL
    SELECT 'c0a80101-0000-4000-8000-000000000003',
           'Giao tiếp & Hỗ trợ',
           'Dễ liên lạc; chủ động phối hợp và sẵn sàng giúp đỡ đồng đội.'
    UNION ALL
    SELECT 'c0a80101-0000-4000-8000-000000000004',
           'Thái độ & Xử lý sự cố',
           'Chịu trách nhiệm với công việc được giao; xử lý sự cố kịp thời và hiệu quả, cởi mở tiếp thu góp ý.'
) v
LEFT JOIN rubric_template existing
    ON existing.subject_id IS NULL
   AND existing.deleted_at IS NULL
WHERE existing.id IS NULL;
