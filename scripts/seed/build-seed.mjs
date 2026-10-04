import { createHash } from "node:crypto";
import { writeFileSync } from "node:fs";

const PWD =
  "$argon2id$v=19$m=19456,t=2,p=1$Cj8j/VuLeV/w6I3sMU3Nfw$uj06bN7xJGmRs/sI3Oih2+cnSFuDPFH3vunN0hj9PJg";
const semesterExpr = "(SELECT id FROM semester WHERE code = 'FA26' AND deleted_at IS NULL LIMIT 1)";
const lecturerUserExpr = "(SELECT id FROM user_account WHERE username = 'lecturer1' AND account_role = 'LECTURER' LIMIT 1)";
const lecturerProfileExpr = "(SELECT id FROM lecturer_profile WHERE user_account_id = (SELECT id FROM user_account WHERE username = 'lecturer1' AND account_role = 'LECTURER' LIMIT 1) LIMIT 1)";
const TODAY = "2026-10-03";
const outPath = new URL("./seed.sql", import.meta.url);

let seq = 0;
const uid = () => {
  seq += 1;
  return `aa110000-0000-4000-8000-${seq.toString(16).padStart(12, "0")}`;
};
const q = (value) => (value == null ? "NULL" : `'${String(value).replaceAll("'", "''")}'`);
const passwordExpr = `CONCAT(${PWD.split("$").filter(Boolean).map((part) => `CHAR(36), ${q(part)}`).join(", ")})`;
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const dayTime = (day, time = "17:00:00") => (day ? `${day} ${time}` : null);

const names = [
  "Nguyễn An", "Trần Bình", "Lê Chi", "Phạm Dũng", "Vũ Hà",
  "Hoàng Vy", "Đỗ Thảo", "Bùi Khoa", "Ngô Mai", "Phan Long",
  "Lý Kim", "Trịnh Nam", "Đặng Oanh", "Mai Phương", "Châu Quân",
  "Đinh Sơn", "Lâm Tuyết", "Hồ Uyên", "Tô Vinh", "La Xuân",
  "Mai Yến", "Kiều Anh", "Quách Bảo", "Trương Chi",
  "Ông Đạt", "Từ Giang", "Vương Hạnh", "Dương Ích", "Nghiêm Khang",
];

const students = names.map((fullName, index) => {
  const cohort = index < 15 ? "17" : "18";
  const serial = String((index < 15 ? index : index - 15) + 1).padStart(3, "0");
  const code = `SE${cohort}0${serial}`;
  return {
    fullName,
    code,
    email: `${code.toLowerCase()}@fpt.edu.vn`,
    username: code.toLowerCase(),
    userId: uid(),
    profileId: uid(),
  };
});

uid();
uid(); // giữ số thứ tự id: lớp vẫn là ...003d / ...003e, course vẫn là ...003f / ...0040
const subjectIdExpr = (code) =>
  `(SELECT id FROM subject WHERE subject_code = '${code}' AND deleted_at IS NULL LIMIT 1)`;
const syllabusExpr = (code) =>
  `(SELECT id FROM subject_syllabus_version WHERE subject_id = ${subjectIdExpr(code)} AND status = 'PUBLISHED' ORDER BY published_at DESC, version_label DESC LIMIT 1)`;
const classA = uid();
const classB = uid();
const courseA = uid();
const courseB = uid();

function makeTeam(name, teamNo, memberIndexes) {
  return {
    name,
    teamNo,
    id: uid(),
    memberIndexes,
    leaderIndex: memberIndexes[0],
    enrollmentIds: memberIndexes.map(() => uid()),
    memberIds: memberIndexes.map(() => uid()),
  };
}

const teams = [
  makeTeam("Nhóm 1", 1, [0, 1, 2, 3, 4]),
  makeTeam("Nhóm 2", 2, [5, 6, 7, 8, 9]),
  makeTeam("Nhóm 3", 3, [10, 11, 12, 13]),
  makeTeam("Nhóm 1", 1, [15, 16, 17, 18, 19]),
  makeTeam("Nhóm 2", 2, [20, 21, 22, 23]),
  makeTeam("Nhóm 3", 3, [24, 25, 26, 27, 28]),
];

const sprintPair = [
  ["Sprint 1", "closed", "2026-09-01", "2026-09-14", "2026-09-14 18:00:00"],
  ["Sprint 2", "closed", "2026-09-15", "2026-09-28", "2026-09-28 18:00:00"],
  ["Sprint 3", "active", "2026-09-29", "2026-10-12", null],
  ["Sprint 4", "future", "2026-10-13", "2026-10-26", null],
];

function sprintsFrom(rows) {
  return rows.map(([name, state, start, end, complete], index) => ({
    name, state, start, end, complete, index, id: uid(), externalId: String(index + 1),
  }));
}

function task(title, type, status, label, points, who, due, done, evidence, delay, sprint, parentTitle) {
  return { title, type, status, label, points, who, due, done, evidence, delay, sprint, parentTitle };
}

const projects = [];
function addProject(spec) {
  const sprints = sprintsFrom(spec.sprints);
  const row = {
    ...spec,
    id: uid(),
    jiraId: uid(),
    repoId: uid(),
    sprints,
    tasks: spec.tasks.map((item, index) => ({
      ...item,
      id: uid(),
      number: index + 1,
    })),
  };
  const byTitle = new Map(row.tasks.map((item) => [item.title, item]));
  let keyNo = 100;
  for (const item of row.tasks) {
    keyNo += 1;
    item.key = `${spec.projectKey}-${keyNo}`;
    item.externalId = String(10000 + keyNo);
    item.sprintRow = item.sprint == null ? null : sprints[item.sprint];
    item.parent = item.parentTitle ? byTitle.get(item.parentTitle) : null;
    item.student = students[spec.team.memberIndexes[item.who]];
    if (!item.student) throw new Error(`${spec.projectKey} ${item.title} has no assignee`);
    if (item.parentTitle && !item.parent) throw new Error(`${spec.projectKey} missing parent ${item.parentTitle}`);
    item.stamp = spec.stamp;
  }
  projects.push(row);
}

const baseSprints = sprintPair;

addProject({
  courseId: courseA,
  team: teams[0],
  projectKey: "LIB",
  name: "Hệ thống quản lý thư viện số",
  description: "Danh mục sách, mượn trả, đặt chỗ, tìm kiếm và phiếu phạt cho thư viện trường.",
  repo: "se1711/thu-vien-so",
  stamp: "2026-10-02 16:00:00",
  jiraOk: true,
  githubOk: true,
  sprints: baseSprints,
  tasks: [
    task("Đăng nhập thủ thư và bạn đọc", "STORY", "DONE", "code", 5, 0, "2026-09-12", "2026-09-10", "commit", null, 0),
    task("Kiểm tra thẻ bạn đọc", "SUBTASK", "DONE", null, 6, 0, "2026-09-11", "2026-09-11", "commit", null, 0, "Đăng nhập thủ thư và bạn đọc"),
    task("Gắn thông báo lỗi đăng nhập", "SUBTASK", "DONE", null, 4, 1, "2026-09-11", "2026-09-11", "commit", null, 0, "Đăng nhập thủ thư và bạn đọc"),
    task("Đặc tả luồng mượn sách", "TASK", "DONE", "document", 3, 1, "2026-09-13", "2026-09-12", "file", null, 0),
    task("Kiểm thử đăng nhập bạn đọc", "TASK", "DONE", "test", 3, 2, "2026-09-12", "2026-09-14", "commit", "CLOSED_SUBJECTIVE", 0),
    task("Đọc quy định mượn và trả sách", "TASK", "DONE", "research", 2, 3, "2026-09-09", "2026-09-13", "link", "CLOSED_OBJECTIVE", 0),
    task("Sửa lỗi giữ phiên đăng nhập", "BUG", "IN_PROGRESS", "code", 3, 4, "2026-09-14", null, null, "OPEN", 0),
    task("Ghi nhận phiếu mượn", "TASK", "DONE", "code", 3, 1, "2026-09-20", "2026-09-16", "commit", null, 1),
    task("Kiểm thử trả sách quá hạn", "TASK", "DONE", "test", 2, 2, "2026-09-22", "2026-09-18", "commit", null, 1),
    task("Hướng dẫn đặt chỗ sách", "TASK", "DONE", "document", 2, 3, "2026-09-24", "2026-09-20", "file", null, 1),
    task("Khảo sát quy trình thư viện", "TASK", "DONE", "research", 2, 4, "2026-09-25", "2026-09-22", "link", null, 1),
    task("Chốt danh mục đầu sách", "TASK", "DONE", "code", 3, 0, "2026-09-27", "2026-09-24", "commit", null, 1),
    task("Gửi thông báo sách đến hạn", "TASK", "DONE", "code", 2, 1, "2026-09-28", "2026-09-26", "commit", null, 1),
    task("Màn hình tìm sách", "STORY", "DONE", "code", 5, 0, "2026-10-02", "2026-10-01", "commit", null, 2),
    task("Lọc sách theo thể loại", "TASK", "DONE", "code", 3, 1, "2026-10-03", "2026-10-02", "commit", null, 2),
    task("Sửa ô tìm kiếm bị trễ", "BUG", "IN_REVIEW", "code", 2, 2, "2026-10-09", null, null, null, 2),
    task("Chặn gõ liên tục vào ô tìm", "SUBTASK", "IN_PROGRESS", null, 6, 3, "2026-10-09", null, null, null, 2, "Sửa ô tìm kiếm bị trễ"),
    task("Hiện gợi ý sách", "SUBTASK", "TODO", null, 4, 4, "2026-10-09", null, null, null, 2, "Sửa ô tìm kiếm bị trễ"),
    task("Kiểm thử bộ lọc thể loại", "TASK", "IN_PROGRESS", "test", 2, 3, "2026-10-10", null, "commit-unlinked", null, 2),
    task("Cập nhật sơ đồ màn hình thư viện", "TASK", "TODO", "document", 1, 4, "2026-10-11", null, null, null, 2),
    task("Tính phí phạt quá hạn", "STORY", "TODO", "code", 5, 0, "2026-10-18", null, null, null, 3),
    task("Kiểm thử tính phí phạt", "TASK", "TODO", "test", 3, 2, "2026-10-20", null, null, null, 3),
    task("Hướng dẫn đóng phạt", "TASK", "TODO", "document", 2, 1, "2026-10-21", null, null, null, 3),
    task("Đối chiếu biểu phí thư viện", "TASK", "TODO", "research", 2, 3, "2026-10-22", null, null, null, 3),
    task("Nhắc hạn đóng phạt", "TASK", "TODO", "code", 1, 4, "2026-10-24", null, null, null, 3),
  ],
});

addProject({
  courseId: courseA,
  team: teams[1],
  projectKey: "ECM",
  name: "Nền tảng thương mại điện tử",
  description: "Gian hàng, giỏ hàng, thanh toán, đơn hàng và theo dõi vận chuyển.",
  repo: "se1711/thuong-mai-dien-tu",
  stamp: "2026-09-27 17:00:00",
  jiraFailed: true,
  sprints: baseSprints,
  tasks: [
    task("Dựng trang gian hàng", "STORY", "DONE", "code", 5, 0, "2026-09-10", "2026-09-08", "commit", null, 0),
    task("Dựng lưới sản phẩm", "SUBTASK", "DONE", null, 6, 1, "2026-09-10", "2026-09-09", "commit", null, 0, "Dựng trang gian hàng"),
    task("Gắn nút thêm vào giỏ", "SUBTASK", "IN_PROGRESS", null, 4, 2, "2026-09-12", null, null, null, 0, "Dựng trang gian hàng"),
    task("Nhập danh mục sản phẩm", "TASK", "DONE", "code", 3, 1, "2026-09-12", "2026-09-11", "commit", null, 0),
    task("Kiểm thử giá và tồn kho", "TASK", "DONE", "test", 2, 2, "2026-09-13", "2026-09-12", "commit", null, 0),
    task("Mô tả thuộc tính sản phẩm", "TASK", "DONE", "document", 2, 3, "2026-09-14", "2026-09-13", "file", null, 0),
    task("So sánh cách tính phí vận chuyển", "TASK", "IN_PROGRESS", "research", 2, 4, "2026-09-14", null, null, null, 0),
    task("Gắn sản phẩm vào giỏ hàng", "TASK", "DONE", "code", 3, 0, "2026-09-20", "2026-09-22", "commit", "CLOSED_SUBJECTIVE", 1),
    task("Đọc quy trình thanh toán", "TASK", "DONE", "research", 2, 1, "2026-09-18", "2026-09-24", "link", "CLOSED_OBJECTIVE", 1),
    task("Viết biên bản đối soát đơn", "TASK", "DONE", "document", 2, 2, "2026-09-26", "2026-09-25", "file", null, 1),
    task("Kiểm thử chuyển trạng thái đơn", "TASK", "DONE", "test", 2, 3, "2026-09-27", "2026-09-26", "commit", null, 1),
    task("Sửa màu trạng thái đơn hủy", "BUG", "TODO", "code", 1, 4, "2026-09-28", null, null, null, 1),
    task("Xuất danh sách đơn trong tuần", "TASK", "DONE", "code", 3, 1, "2026-09-26", "2026-09-27", null, null, 2),
    task("Cập nhật số lượng còn lại", "TASK", "IN_PROGRESS", "code", 3, 2, "2026-09-30", null, null, "OPEN", 2),
    task("Rà đơn với sổ kho", "TASK", "IN_REVIEW", "document", 2, 3, "2026-09-25", null, null, "AWAITING_LEADER", 2),
    task("Giải thích chênh số lượng tồn", "TASK", "TODO", "research", 2, 4, "2026-09-26", null, null, "AWAITING_LECTURER", 2),
    task("Lỗi lệch cột thành tiền", "BUG", "BLOCKED", "test", 2, 1, "2026-10-08", null, null, null, 2),
    task("Nối bộ lọc đơn theo khách", "TASK", "IN_PROGRESS", "code", 3, 0, "2026-10-11", null, null, null, 2),
    task("Thêm theo dõi vận đơn", "TASK", "TODO", "code", 3, 0, "2026-10-18", null, null, null, 3),
    task("Kiểm thử bộ lọc khách hàng", "TASK", "TODO", "test", 2, 2, "2026-10-20", null, null, null, 3),
    task("Viết chú thích trạng thái giao hàng", "TASK", "TODO", "document", 1, 3, "2026-10-21", null, null, null, 3),
    task("Đối chiếu công thức phí vận chuyển", "TASK", "TODO", "research", 2, 4, "2026-10-22", null, null, null, 3),
    task("Khóa đơn khi đã giao", "TASK", "TODO", "code", 2, 1, "2026-10-24", null, null, null, 3),
  ],
});

addProject({
  courseId: courseA,
  team: teams[2],
  projectKey: "CLN",
  name: "Ứng dụng đặt lịch khám bệnh",
  description: "Lịch bác sĩ, đặt khám, hồ sơ bệnh nhân, nhắc lịch và nhận xét sau buổi khám.",
  repo: "se1711/dat-lich-kham",
  stamp: "2026-10-01 15:00:00",
  reviews: 3,
  sprints: [
    sprintPair[0],
    sprintPair[1],
    ["Sprint 3", "active", "2026-09-15", "2026-10-02", null],
    sprintPair[3],
  ],
  tasks: [
    task("Phác phiếu thông tin bệnh nhân", "STORY", "DONE", "document", 3, 0, "2026-09-10", "2026-09-09", "file", null, 0),
    task("Tạo API lưu lịch khám", "TASK", "DONE", "code", 5, 1, "2026-09-12", "2026-09-11", "commit", null, 0),
    task("Lưu khung giờ bác sĩ", "SUBTASK", "DONE", null, 6, 2, "2026-09-12", "2026-09-11", "commit", null, 0, "Tạo API lưu lịch khám"),
    task("Kiểm tra suất còn trống", "SUBTASK", "DONE", null, 4, 3, "2026-09-12", "2026-09-12", "commit", null, 0, "Tạo API lưu lịch khám"),
    task("Kiểm thử lưu một lịch khám", "TASK", "DONE", "test", 2, 2, "2026-09-13", "2026-09-12", "commit", null, 0),
    task("Đọc quy tắc đặt khám", "TASK", "DONE", "research", 2, 3, "2026-09-14", "2026-09-13", "link", null, 0),
    task("Ẩn suất khi đã kín", "BUG", "DONE", "code", 2, 0, "2026-09-14", "2026-09-14", "commit", null, 0),
    task("Màn danh sách lịch khám", "TASK", "DONE", "code", 3, 1, "2026-09-20", "2026-09-18", "commit", null, 1),
    task("Chặn đặt trùng giờ", "TASK", "DONE", "code", 2, 2, "2026-09-22", "2026-09-21", "commit", null, 1),
    task("Kiểm thử cặp bác sĩ và bệnh nhân", "TASK", "DONE", "test", 2, 3, "2026-09-24", "2026-09-23", "commit", null, 1),
    task("Viết quy trình xác nhận lịch", "TASK", "DONE", "document", 2, 0, "2026-09-26", "2026-09-25", "file", null, 1),
    task("Tổng hợp lượt khám trong ngày", "TASK", "IN_PROGRESS", "code", 3, 1, "2026-09-28", null, null, null, 1),
    task("Khóa lịch khi hết hạn", "STORY", "DONE", "code", 5, 0, "2026-09-28", "2026-09-27", "commit", null, 2),
    task("Hiện bệnh nhân chưa xác nhận", "TASK", "DONE", "code", 3, 1, "2026-09-30", "2026-09-29", "commit", null, 2),
    task("Kiểm thử đủ suất trong ngày", "TASK", "DONE", "test", 3, 2, "2026-10-01", "2026-09-30", "commit", null, 2),
    task("Ghi chú sau buổi khám", "TASK", "DONE", "document", 2, 3, "2026-10-01", "2026-10-01", "file", null, 2),
    task("Soát lại khung giờ", "TASK", "DONE", "research", 2, 0, "2026-10-02", "2026-10-01", "link", null, 2),
    task("Nhắc bệnh nhân còn thiếu xác nhận", "TASK", "IN_PROGRESS", "code", 2, 1, "2026-10-10", null, null, null, 2),
    task("Xuất phiếu khám", "TASK", "TODO", "document", 2, 0, "2026-10-18", null, null, null, 3),
    task("Kiểm thử file phiếu khám", "TASK", "TODO", "test", 2, 2, "2026-10-20", null, null, null, 3),
    task("Mẫu nhận xét sau khám", "TASK", "TODO", "document", 1, 3, "2026-10-21", null, null, null, 3),
    task("Đối chiếu quy trình phòng khám", "TASK", "TODO", "research", 2, 1, "2026-10-22", null, null, null, 3),
    task("Gửi nhắc lịch cho bệnh nhân", "TASK", "TODO", "code", 2, 0, "2026-10-24", null, null, null, 3),
  ],
});

addProject({
  courseId: courseB,
  team: teams[3],
  projectKey: "KTX",
  name: "Hệ thống quản lý ký túc xá",
  description: "Phòng ở, đăng ký chỗ, hóa đơn điện nước, sự cố phòng và nội quy.",
  repo: "se1811/ky-tuc-xa",
  stamp: "2026-10-02 11:00:00",
  weights: true,
  sprints: baseSprints,
  tasks: [
    task("Quét mã phòng", "TASK", "DONE", "code", null, 0, "2026-09-12", "2026-09-11", "commit", null, 0),
    task("Lưu lượt đăng ký chỗ", "TASK", "DONE", "code", 3, 1, "2026-09-13", "2026-09-12", "commit", null, 0),
    task("Kiểm thử một lượt đăng ký", "TASK", "DONE", "test", 2, 2, "2026-09-14", "2026-09-13", "commit", null, 0),
    task("Mô tả luồng nhận phòng", "TASK", "DONE", "document", 2, 3, "2026-09-14", "2026-09-13", "file", null, 0),
    task("Viết bước nhận phòng", "SUBTASK", "DONE", null, 6, 0, "2026-09-14", "2026-09-13", "file", null, 0, "Mô tả luồng nhận phòng"),
    task("Viết bước trả phòng", "SUBTASK", "DONE", null, 4, 1, "2026-09-14", "2026-09-13", "file", null, 0, "Mô tả luồng nhận phòng"),
    task("Xem cách ký túc xá khác xếp phòng", "TASK", "DONE", "research", 2, 4, "2026-09-14", "2026-09-14", "link", null, 0),
    task("Sửa màn hình sơ đồ phòng", "TASK", "DONE", "both", 3, 1, "2026-09-22", "2026-09-20", "commit", null, 1),
    task("Viết hướng dẫn quản lý", "TASK", "DONE", "document", 2, 2, "2026-09-24", "2026-09-23", null, null, 1),
    task("Gia hạn chỗ ở", "TASK", "DONE", "code", 3, 0, "2026-09-26", "2026-09-25", "commit", null, 1),
    task("Kiểm thử trả phòng trễ", "TASK", "DONE", "test", 2, 3, "2026-09-27", "2026-09-26", "commit", null, 1),
    task("Ghi chú phòng trống", "TASK", "IN_PROGRESS", "document", 1, 4, "2026-09-28", null, null, null, 1),
    task("Danh sách sinh viên đang ở", "STORY", "DONE", "code", 5, 0, "2026-10-02", "2026-10-01", "commit", null, 2),
    task("Lọc theo tòa nhà", "TASK", "IN_PROGRESS", "code", 3, 1, "2026-10-10", null, "commit", null, 2),
    task("Kiểm thử bộ lọc tòa nhà", "TASK", "TODO", "test", 2, 2, "2026-10-11", null, null, null, 2),
    task("Cập nhật sơ đồ mặt bằng", "TASK", "TODO", "document", 1, 3, "2026-10-11", null, null, null, 2),
    task("Xuất hóa đơn điện nước", "TASK", "IN_PROGRESS", "code", 3, 4, "2026-10-12", null, null, null, 2),
    task("Nhập chỉ số đầu tháng", "TASK", "TODO", "code", 3, 0, "2026-10-18", null, null, null, 3),
    task("Kiểm thử chỉ số điện nước", "TASK", "TODO", "test", 2, 2, "2026-10-20", null, null, null, 3),
    task("Mẫu biên bản sự cố phòng", "TASK", "TODO", "document", 2, 1, "2026-10-21", null, null, null, 3),
    task("Tìm nội quy ký túc xá", "TASK", "TODO", "research", 2, 3, "2026-10-22", null, null, null, 3),
    task("Nhắc phòng chưa chốt chỉ số", "TASK", "TODO", "code", 1, 4, "2026-10-24", null, null, null, 3),
    task("Đồng bộ danh sách phòng", "TASK", "DONE", "code", 3, 0, null, "2026-09-20", "commit", null, null),
  ],
});

addProject({
  courseId: courseB,
  team: teams[5],
  projectKey: "VLM",
  name: "Cổng việc làm cho sinh viên",
  description: "Tin tuyển dụng, hồ sơ ứng viên, nộp đơn, lịch phỏng vấn và theo dõi kết quả.",
  repo: "se1811/cong-viec-lam",
  stamp: "2026-10-02 09:00:00",
  githubFailed: true,
  sprints: [
    sprintPair[0],
    sprintPair[1],
    ["Sprint 3", "closed", "2026-09-29", "2026-10-12", "2026-10-02 18:00:00"],
    sprintPair[3],
  ],
  tasks: [
    task("Mẫu hồ sơ ứng viên", "TASK", "DONE", "document", 2, 0, "2026-09-10", "2026-09-09", "file", null, 0),
    task("Trang nhập tin tuyển dụng", "TASK", "DONE", "code", 3, 1, "2026-09-12", "2026-09-11", "commit", null, 0),
    task("Form tiêu đề và hạn nộp", "SUBTASK", "DONE", null, 6, 0, "2026-09-12", "2026-09-11", "commit", null, 0, "Trang nhập tin tuyển dụng"),
    task("Form mức lương", "SUBTASK", "TODO", null, 4, 2, "2026-09-13", null, null, null, 0, "Trang nhập tin tuyển dụng"),
    task("Kiểm thử lưu nháp tin", "TASK", "DONE", "test", 2, 2, "2026-09-13", "2026-09-12", "commit", null, 0),
    task("Xem tin các đợt trước", "TASK", "DONE", "research", 1, 3, "2026-09-14", "2026-09-13", "link", null, 0),
    task("Sửa tiêu đề tin bị cắt", "BUG", "DONE", "code", 1, 4, "2026-09-14", "2026-09-14", "commit", null, 0),
    task("Nộp hồ sơ đợt hai", "TASK", "DONE", "document", 2, 0, "2026-09-20", "2026-09-19", "file", null, 1),
    task("Gắn file CV", "TASK", "DONE", "code", 3, 1, "2026-09-22", "2026-09-21", "commit", null, 1),
    task("Kiểm thử file đính kèm", "TASK", "DONE", "test", 2, 2, "2026-09-24", "2026-09-23", "commit", null, 1),
    task("Chốt nhà tuyển dụng tuần này", "TASK", "DONE", "document", 1, 3, "2026-09-26", "2026-09-25", "file", null, 1),
    task("Đối chiếu mục hồ sơ còn thiếu", "TASK", "IN_PROGRESS", "research", 2, 4, "2026-09-28", null, null, null, 1),
    task("Nộp đơn đợt ba", "TASK", "DONE", "document", 2, 0, "2026-10-02", "2026-10-01", "file", null, 2),
    task("Khóa đơn đã nộp", "TASK", "DONE", "code", 3, 1, "2026-10-02", "2026-10-02", "commit", null, 2),
    task("Kiểm thử không sửa đơn sau khi nộp", "TASK", "DONE", "test", 2, 2, "2026-10-03", "2026-10-02", "commit", null, 2),
    task("Ghi phần việc vòng phỏng vấn", "TASK", "DONE", "document", 1, 3, "2026-10-04", "2026-10-02", "file", null, 2),
    task("Tóm tắt phản hồi nhà tuyển dụng", "TASK", "IN_PROGRESS", "research", 2, 4, "2026-10-08", null, null, null, 2),
    task("Mẫu thư mời phỏng vấn", "TASK", "TODO", "document", 3, 0, "2026-10-18", null, null, null, 3),
    task("Trang lịch phỏng vấn", "TASK", "TODO", "code", 2, 1, "2026-10-20", null, null, null, 3),
    task("Kiểm thử xuất PDF hồ sơ", "TASK", "TODO", "test", 2, 2, "2026-10-21", null, null, null, 3),
    task("Danh mục kỹ năng", "TASK", "TODO", "document", 1, 3, "2026-10-22", null, null, null, 3),
    task("Rà mục hồ sơ bắt buộc", "TASK", "TODO", "research", 2, 4, "2026-10-24", null, null, null, 3),
  ],
});

addProject({
  courseId: courseB,
  team: teams[4],
  projectKey: "SKN",
  name: "Cổng đăng ký sự kiện",
  description: "Sự kiện câu lạc bộ, phiếu đăng ký, nhắc đóng phí và danh sách người tham dự.",
  repo: "se1811/cong-dang-ky-su-kien",
  stamp: "2026-10-02 14:00:00",
  weights: true,
  sprints: baseSprints,
  tasks: [
    task("Tạo trang sự kiện", "STORY", "DONE", "code", 5, 0, "2026-09-12", "2026-09-10", "commit", null, 0),
    task("Form tên và thời gian", "SUBTASK", "DONE", null, 6, 1, "2026-09-11", "2026-09-11", "commit", null, 0, "Tạo trang sự kiện"),
    task("Form địa điểm", "SUBTASK", "DONE", null, 4, 2, "2026-09-11", "2026-09-11", "commit", null, 0, "Tạo trang sự kiện"),
    task("Kiểm thử lưu sự kiện", "TASK", "DONE", "test", 2, 2, "2026-09-13", "2026-09-12", "commit", null, 0),
    task("Mô tả quy trình đăng ký", "TASK", "DONE", "document", 2, 3, "2026-09-14", "2026-09-13", "file", null, 0),
    task("Đọc quy định tổ chức sự kiện", "TASK", "DONE", "research", 2, 0, "2026-09-14", "2026-09-14", "link", null, 0),
    task("Màn hình phiếu đăng ký", "TASK", "DONE", "code", 3, 1, "2026-09-20", "2026-09-18", "commit", null, 1),
    task("Chặn đăng ký khi đã đủ chỗ", "TASK", "DONE", "code", 3, 0, "2026-09-22", "2026-09-21", "commit", null, 1),
    task("Kiểm thử giới hạn chỗ", "TASK", "DONE", "test", 2, 2, "2026-09-24", "2026-09-23", "commit", null, 1),
    task("Hướng dẫn ban tổ chức", "TASK", "DONE", "document", 2, 3, "2026-09-26", "2026-09-25", "file", null, 1),
    task("Gửi thư xác nhận đăng ký", "TASK", "DONE", "code", 2, 1, "2026-09-27", "2026-09-26", "commit", null, 1),
    task("Duyệt danh sách đăng ký", "TASK", "IN_PROGRESS", "code", 3, 0, "2026-09-30", null, null, "OPEN", 2),
    task("Gửi thư nhắc đóng phí", "TASK", "TODO", "code", 2, 1, "2026-10-01", null, null, "AWAITING_LEADER", 2),
    task("Xuất danh sách tham dự", "TASK", "IN_PROGRESS", "code", 3, 2, "2026-10-10", null, null, null, 2),
    task("Kiểm thử file danh sách", "TASK", "TODO", "test", 2, 3, "2026-10-11", null, null, null, 2),
    task("Cập nhật sơ đồ chỗ ngồi", "TASK", "TODO", "document", 1, 0, "2026-10-12", null, null, null, 2),
    task("Trang điểm danh tại cửa", "TASK", "TODO", "code", 3, 0, "2026-10-18", null, null, null, 3),
    task("Kiểm thử quét vé", "TASK", "TODO", "test", 2, 2, "2026-10-20", null, null, null, 3),
    task("Mẫu vé điện tử", "TASK", "TODO", "document", 2, 1, "2026-10-21", null, null, null, 3),
    task("Đối chiếu quy định vé", "TASK", "TODO", "research", 2, 3, "2026-10-22", null, null, null, 3),
    task("Nhắc sự kiện sắp diễn ra", "TASK", "TODO", "code", 1, 1, "2026-10-24", null, null, null, 3),
  ],
});

function completion(item) {
  if (item.status === "DONE") {
    if (!item.due || !item.done) return "COMPLETED_NO_DUE_DATE";
    return item.done <= item.due ? "COMPLETED_ON_TIME" : "COMPLETED_LATE";
  }
  if (item.due && item.due < TODAY) return "OVERDUE";
  if (item.status === "TODO") return "NOT_STARTED";
  return "IN_PROGRESS";
}

function jiraStatus(status) {
  return {
    DONE: ["Done", "done"],
    IN_PROGRESS: ["In Progress", "indeterminate"],
    IN_REVIEW: ["In Review", "indeterminate"],
    TODO: ["To Do", "new"],
    BLOCKED: ["Blocked", "indeterminate"],
  }[status];
}

function issueMeta(type) {
  return {
    EPIC: ["Epic", "10000", "EPIC", 1],
    STORY: ["Story", "10001", "STANDARD", 0],
    TASK: ["Task", "10002", "STANDARD", 0],
    BUG: ["Bug", "10003", "STANDARD", 0],
    SUBTASK: ["Subtask", "10004", "SUBTASK", -1],
  }[type];
}

function labels(label) {
  if (label === "both") return `'["saga:code","saga:test"]'`;
  if (!label) return "NULL";
  return `'["saga:${label}"]'`;
}

const lines = [];
const push = (line = "") => lines.push(line);
const delays = [];
const files = [];
const webLinks = [];
const commits = [];
const commitLinks = [];
const reviews = [];
const jobs = [];
const weightRows = [];

let evidenceNo = 0;
for (const project of projects) {
  for (const item of project.tasks) {
    if (item.delay) {
      delays.push({
        id: uid(),
        projectId: project.id,
        task: item,
        status: item.delay,
      });
    }
    if (item.evidence === "file") {
      evidenceNo += 1;
      files.push({
        id: uid(),
        task: item,
        filename: `${item.key.toLowerCase()}.pdf`,
        hash: sha256(`file-${item.key}`),
      });
    }
    if (item.evidence === "link") {
      const site = project.courseId === courseA ? "prm392" : "swd392";
      const url = `https://${site}.invalid/notes/${item.key.toLowerCase()}`;
      webLinks.push({
        id: uid(),
        task: item,
        url,
        hash: sha256(url),
        title: item.title,
      });
    }
    if (item.evidence === "commit" || item.evidence === "commit-unlinked") {
      evidenceNo += 1;
      const commit = {
        id: uid(),
        repoId: project.repoId,
        author: item.student,
        sha: sha256(`commit-${item.key}`).slice(0, 40),
        message: `feat:[${item.key}] ${item.title.charAt(0).toLowerCase()}${item.title.slice(1)}`,
        at: dayTime(item.done ?? item.due ?? "2026-10-01", "15:30:00"),
      };
      commits.push(commit);
      if (item.evidence === "commit") {
        commitLinks.push({ id: uid(), taskId: item.id, commitId: commit.id, key: item.key });
      }
    }
  }
  if (project.jiraOk) {
    jobs.push({ id: uid(), system: "JIRA", projectId: project.id, status: "SUCCEEDED", at: "2026-10-02 16:10:00" });
  }
  if (project.githubOk) {
    jobs.push({ id: uid(), system: "GITHUB", projectId: project.id, status: "SUCCEEDED", at: "2026-10-02 16:12:00" });
  }
  if (project.jiraFailed) {
    jobs.push({ id: uid(), system: "JIRA", projectId: project.id, status: "FAILED", at: "2026-09-27 17:20:00" });
  }
  if (project.githubFailed) {
    jobs.push({ id: uid(), system: "GITHUB", projectId: project.id, status: "FAILED", at: "2026-10-01 09:00:00" });
  }
  if (project.weights) {
    weightRows.push({ id: uid(), projectId: project.id, teamId: project.team.id });
  }
  if (project.reviews) {
    const members = project.team.memberIndexes.map((index) => students[index]);
    const pairs = [];
    for (const reviewer of members) {
      for (const reviewee of members) {
        if (reviewer !== reviewee) pairs.push([reviewer, reviewee]);
      }
    }
    for (const [reviewer, reviewee] of pairs.slice(0, project.reviews)) {
      reviews.push({
        id: uid(),
        sprintId: project.sprints[2].id,
        reviewer,
        reviewee,
      });
    }
  }
}

const taskIds = projects.flatMap((project) => project.tasks.map((item) => item.id));
const sprintIds = projects.flatMap((project) => project.sprints.map((item) => item.id));
const inList = (values) => values.map((value) => q(value)).join(", ");

push("-- Class data for PRM392-SE1711 and SWD392-SE1811 in Fall 2026.");
push("-- Not a Flyway migration. Requires lecturer1 (Nguyễn Ngân) and semester FA26.");
push("-- This script does not create the lecturer account or the semester.");
push("--");
push("-- Student password: Saga@2026fe");
push("-- Student code: SE + khoa 17/18 + 0 + 3 so cuoi. Vi du SE170001, email se170001@fpt.edu.vn.");
push("-- Moi de tai co subtask. Story point 6 va 4 la 60% va 40% cua cha. Subtask khong co nhan, sprint theo cha.");
push("-- Script does not create subjects or syllabi. Courses use the published syllabus already stored for PRM392 and SWD392.");
push("-- PRM392 / SE1711 pins the existing published syllabus.");
push("-- PRM392 / SE1711 / Nhóm 1 Hệ thống quản lý thư viện số: sát lịch, đóng góp, đúng hạn, burndown.");
push("-- PRM392 / SE1711 / Nhóm 2 Nền tảng thương mại điện tử: chậm, quá hạn, kẹt, im từ 27/9, Jira FAILED.");
push("-- PRM392 / SE1711 / Nhóm 3 Ứng dụng đặt lịch khám bệnh: sprint đã qua hạn, phiếu nhận xét chưa đủ.");
push("-- SWD392 / SE1811 pins the existing published syllabus and uses PROJECT_GROUP weights.");
push("-- SWD392 / SE1811 / Nhóm 1 Hệ thống quản lý ký túc xá: việc được điểm và việc không đủ điều kiện.");
push("-- SWD392 / SE1811 / Nhóm 2 Cổng đăng ký sự kiện: sprint hiện tại chưa xong việc, có task trễ hạn.");
push("-- SWD392 / SE1811 / Nhóm 3 Cổng việc làm cho sinh viên: không có sprint đang chạy, GitHub FAILED, thiếu trọng số.");
push("-- Châu Quân SE170015 ghi danh PRM392-SE1711 và chưa vào nhóm.");
push("");
push("SET NAMES utf8mb4;");
push("");
push("START TRANSACTION;");
push("");
const unassignedEnrollmentId = uid();
push(`UPDATE task SET parent_task_id = NULL, blocks_task_id = NULL WHERE id IN (${inList(taskIds)});`);
push(`DELETE FROM task_delay_case WHERE project_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM task_git_commit_link WHERE task_id IN (${inList(taskIds)});`);
push(`DELETE FROM git_commit WHERE repo_id IN (${inList(projects.map((item) => item.repoId))});`);
push(`DELETE FROM peer_review WHERE sprint_id IN (${inList(sprintIds)});`);
push(`DELETE FROM task WHERE project_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM sprint WHERE jira_integration_id IN (${inList(projects.map((item) => item.jiraId))});`);
push(`DELETE FROM sync_job_log WHERE id IN (${inList(jobs.map((item) => item.id))}) OR target_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM jira_integration WHERE project_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM git_repo WHERE project_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM project_group_weight_config WHERE project_id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM team_member WHERE team_id IN (${inList(teams.map((team) => team.id))});`);
push(`DELETE FROM team WHERE id IN (${inList(teams.map((team) => team.id))});`);
push(`DELETE FROM project WHERE id IN (${inList(projects.map((item) => item.id))});`);
push(`DELETE FROM course_enrollment WHERE course_id IN (${q(courseA)}, ${q(courseB)}) OR student_profile_id IN (${inList(students.map((student) => student.profileId))});`);
push(`DELETE FROM student_course_invitation WHERE course_id IN (${q(courseA)}, ${q(courseB)});`);
push(`DELETE FROM course WHERE id IN (${q(courseA)}, ${q(courseB)});`);
push(`DELETE FROM academic_class WHERE id IN (${q(classA)}, ${q(classB)});`);
const seedUserIds = inList(students.map((student) => student.userId));
push(`DELETE d FROM notification_delivery d JOIN user_notification n ON n.id = d.notification_id WHERE n.recipient_user_id IN (${seedUserIds});`);
push(`DELETE FROM user_notification WHERE recipient_user_id IN (${seedUserIds});`);
push(`DELETE FROM email_outbox WHERE recipient_user_id IN (${seedUserIds});`);
push(`DELETE d FROM notification_delivery d JOIN firebase_installation f ON f.id = d.installation_id WHERE f.owner_user_id IN (${seedUserIds});`);
push(`DELETE FROM firebase_installation WHERE owner_user_id IN (${seedUserIds});`);
push(`DELETE FROM password_reset_token WHERE user_id IN (${seedUserIds});`);
push(`DELETE FROM webauthn_credential WHERE user_account_id IN (${seedUserIds});`);
push(`DELETE FROM identity_mapping_history WHERE user_account_id IN (${seedUserIds}) OR actor_user_id IN (${seedUserIds});`);
push(`DELETE FROM identity_map WHERE user_account_id IN (${seedUserIds}) OR reviewed_by_user_id IN (${seedUserIds});`);
push(`DELETE FROM audit_log WHERE actor_user_id IN (${seedUserIds});`);
push(`DELETE FROM student_profile WHERE user_account_id IN (${seedUserIds});`);
push(`DELETE FROM user_account WHERE id IN (${seedUserIds});`);
push("");

push("INSERT INTO user_account (id, email, username, full_name, password_hash, account_role, account_status) VALUES");
push(students.map((student) => `    (${q(student.userId)}, ${q(student.email)}, ${q(student.username)}, ${q(student.fullName)}, ${passwordExpr}, 'STUDENT', 'ACTIVE')`).join(",\n") + ";");
push("");
push("INSERT INTO student_profile (id, user_account_id, student_code, approved_by_user_id, approved_at) VALUES");
push(students.map((student) => `    (${q(student.profileId)}, ${q(student.userId)}, ${q(student.code)}, ${lecturerUserExpr}, '2026-09-02 09:00:00')`).join(",\n") + ";");
push("");
push(`INSERT INTO academic_class (id, semester_id, class_code, name) VALUES`);
push(`    (${q(classA)}, ${semesterExpr}, 'SE1711', 'SE1711'),`);
push(`    (${q(classB)}, ${semesterExpr}, 'SE1811', 'SE1811');`);
push(`INSERT INTO course (id, subject_id, syllabus_version_id, academic_class_id, semester_id, instructor_id, course_code, name, code_contribution_weight, test_contribution_weight, document_contribution_weight, research_contribution_weight, contribution_config_mode) VALUES`);
push(`    (${q(courseA)}, ${subjectIdExpr("PRM392")}, ${syllabusExpr("PRM392")}, ${q(classA)}, ${semesterExpr}, ${lecturerProfileExpr}, 'PRM392-SE1711', 'PRM392 - SE1711 - Fall 2026', 25, 25, 25, 25, 'COURSE'),`);
push(`    (${q(courseB)}, ${subjectIdExpr("SWD392")}, ${syllabusExpr("SWD392")}, ${q(classB)}, ${semesterExpr}, ${lecturerProfileExpr}, 'SWD392-SE1811', 'SWD392 - SE1811 - Fall 2026', 25, 25, 25, 25, 'PROJECT_GROUP');`);
push("");

const enrollments = [];
for (const project of projects) {
  project.team.memberIndexes.forEach((studentIndex, memberIndex) => {
    enrollments.push({
      id: project.team.enrollmentIds[memberIndex],
      student: students[studentIndex],
      courseId: project.courseId,
    });
  });
}
enrollments.push({ id: unassignedEnrollmentId, student: students[14], courseId: courseA });
push("INSERT INTO course_enrollment (id, student_profile_id, course_id, enrollment_status, enrolled_at) VALUES");
push(enrollments.map((row) => `    (${q(row.id)}, ${q(row.student.profileId)}, ${q(row.courseId)}, 'ACTIVE', '2026-09-02 09:30:00')`).join(",\n") + ";");
push("");
push("INSERT INTO project (id, course_id, project_type_id, name, description, created_by_user_id) VALUES");
push(projects.map((project) => {
  const leader = students[project.team.leaderIndex];
  return `    (${q(project.id)}, ${q(project.courseId)}, '11111111-1111-1111-1111-111111111111', ${q(project.name)}, ${q(project.description)}, ${q(leader.userId)})`;
}).join(",\n") + ";");
push("");
push("INSERT INTO team (id, course_id, team_no, project_id, name) VALUES");
push(projects.map((project) => `    (${q(project.team.id)}, ${q(project.courseId)}, ${project.team.teamNo}, ${q(project.id)}, ${q(project.team.name)})`).join(",\n") + ";");
push("");
push("INSERT INTO team_member (id, team_id, course_id, course_enrollment_id, role_in_team) VALUES");
const memberRows = [];
function pushMembers(team, courseId) {
  team.memberIndexes.forEach((studentIndex, memberIndex) => {
    const role = studentIndex === team.leaderIndex ? "LEADER" : "MEMBER";
    memberRows.push(`    (${q(team.memberIds[memberIndex])}, ${q(team.id)}, ${q(courseId)}, ${q(team.enrollmentIds[memberIndex])}, '${role}')`);
  });
}
for (const project of projects) pushMembers(project.team, project.courseId);
push(memberRows.join(",\n") + ";");
push("");
push("INSERT INTO jira_integration (id, project_id, name, board_type, cloud_id, site_url, site_name, jira_project_id, project_key, connection_status, connected_by_user_id) VALUES");
push(projects.map((project, index) => {
  const leader = students[project.team.leaderIndex];
  const subjectCode = project.courseId === courseA ? "PRM392" : "SWD392";
  return `    (${q(project.jiraId)}, ${q(project.id)}, ${q("Scrum " + project.name)}, 'SCRUM', ${q(subjectCode.toLowerCase() + "-" + project.projectKey.toLowerCase())}, ${q("https://" + subjectCode.toLowerCase() + ".atlassian.net")}, ${q(subjectCode)}, ${q(String(20001 + index))}, ${q(project.projectKey)}, 'DISCONNECTED', ${q(leader.userId)})`;
}).join(",\n") + ";");
push("");
push("INSERT INTO sprint (id, jira_integration_id, name, external_sprint_id, start_date, end_date, goal, state, complete_date) VALUES");
const sprintRows = [];
for (const project of projects) {
  for (const sprint of project.sprints) {
    sprintRows.push(`    (${q(sprint.id)}, ${q(project.jiraId)}, ${q(sprint.name)}, ${q(project.projectKey + "-" + sprint.externalId)}, ${q(sprint.start + " 00:00:00")}, ${q(sprint.end + (sprint.end.length === 10 ? " 23:59:59" : ""))}, ${q(project.name + " - " + sprint.name)}, ${q(sprint.state)}, ${q(sprint.complete)})`);
  }
}
push(sprintRows.join(",\n") + ";");
push("");

function taskTuple(project, item) {
  const [statusName, category] = jiraStatus(item.status);
  const [issueName, issueId, level, hierarchy] = issueMeta(item.type);
  const reporter = students[project.team.leaderIndex];
  return `    (${q(item.id)}, ${q(project.id)}, ${q(project.jiraId)}, ${q(item.sprintRow ? item.sprintRow.id : null)}, ${q(item.student.profileId)}, ${q(reporter.profileId)}, ${q(item.key)}, ${q(item.externalId)}, ${q(item.parent ? item.parent.externalId : null)}, ${q(item.parent ? item.parent.key : null)}, ${q(item.parent ? item.parent.id : null)}, ${q(item.title)}, ${q(item.type)}, ${q(item.status)}, ${q(statusName)}, ${q(category)}, ${q(issueName)}, ${q(issueId)}, ${q(level)}, ${hierarchy}, ${q(completion(item))}, 'MEDIUM', ${item.points == null ? "NULL" : item.points}, ${labels(item.label)}, ${q(dayTime(item.due))}, ${q(item.done ? dayTime(item.due, "08:00:00") : null)}, ${q(item.status === "DONE" ? dayTime(item.done, "16:00:00") : null)}, ${q(item.status === "DONE" ? dayTime(item.done, "16:00:00") : null)}, ${q(item.title)}, ${q(project.stamp)}, ${q(project.stamp)})`;
}

const taskColumns = `INSERT INTO task (
    id, project_id, jira_integration_id, sprint_id, assignee_student_id, reporter_student_id,
    external_key, external_id, parent_external_id, parent_external_key, parent_task_id,
    title, task_type, status, jira_status_name, jira_status_category,
    issue_type_name, issue_type_id, issue_type_level, jira_hierarchy_level,
    saga_completion_state, priority, story_point, labels_json, due_date, start_date,
    resolved_at, completed_at, description, created_at, updated_at
) VALUES`;
const parents = [];
const children = [];
for (const project of projects) {
  for (const item of project.tasks) {
    (item.parent ? children : parents).push(taskTuple(project, item));
  }
}
push(taskColumns);
push(parents.join(",\n") + ";");
push(taskColumns);
push(children.join(",\n") + ";");
push("");

if (delays.length) {
  push(`INSERT INTO task_delay_case (
    id, project_id, task_id, student_profile_id, due_date, opened_at, explanation_due_at,
    status, category, explanation_note, explained_at, explained_by_user_id, closed_at
) VALUES`);
  push(delays.map((row) => {
    const due = dayTime(row.task.due);
    const opened = dayTime(row.task.due, "18:00:00");
    const explained = ["AWAITING_LEADER", "AWAITING_LECTURER", "CLOSED_OBJECTIVE", "CLOSED_SUBJECTIVE"].includes(row.status);
    const category = row.status === "AWAITING_LECTURER" ? "OTHER" : row.status === "CLOSED_OBJECTIVE" ? "TECHNICAL_ISSUE" : row.status === "CLOSED_SUBJECTIVE" ? "STARTED_LATE" : row.status === "AWAITING_LEADER" ? "STARTED_LATE" : null;
    const note = explained ? "Đã ghi lại lý do trễ hạn của việc này." : null;
    const closed = row.status.startsWith("CLOSED") ? projectStamp(row) : null;
    return `    (${q(row.id)}, ${q(row.projectId)}, ${q(row.task.id)}, ${q(row.task.student.profileId)}, ${q(due)}, ${q(opened)}, ${q(dayTime(row.task.due, "18:00:00"))}, ${q(row.status)}, ${q(category)}, ${q(note)}, ${q(explained ? opened : null)}, ${q(explained ? row.task.student.userId : null)}, ${q(closed)})`;
  }).join(",\n") + ";");
  push("");
}

function projectStamp(row) {
  return projects.find((project) => project.id === row.projectId).stamp;
}

if (files.length) {
  push("INSERT INTO task_file (id, task_id, original_filename, mime_type, size_bytes, content_hash, source, created_by_user_id) VALUES");
  push(files.map((row) => `    (${q(row.id)}, ${q(row.task.id)}, ${q(row.filename)}, 'application/pdf', 48000, ${q(row.hash)}, 'SAGA', ${q(row.task.student.userId)})`).join(",\n") + ";");
  push("");
}
if (webLinks.length) {
  push("INSERT INTO task_web_link (id, task_id, url, url_hash, title, source, created_by_user_id) VALUES");
  push(webLinks.map((row) => `    (${q(row.id)}, ${q(row.task.id)}, ${q(row.url)}, ${q(row.hash)}, ${q(row.title)}, 'SAGA', ${q(row.task.student.userId)})`).join(",\n") + ";");
  push("");
}
push("INSERT INTO git_repo (id, project_id, name, url, provider, full_name, default_branch, connection_status) VALUES");
push(projects.map((project) => `    (${q(project.repoId)}, ${q(project.id)}, ${q(project.repo.split("/")[1])}, ${q("https://github.com/" + project.repo)}, 'GITHUB', ${q(project.repo)}, 'main', 'DISCONNECTED')`).join(",\n") + ";");
push("");
push("INSERT INTO git_commit (id, repo_id, author_student_id, sha_hash, message, committed_at, additions, deletions, files_changed, parent_count) VALUES");
push(commits.map((row) => `    (${q(row.id)}, ${q(row.repoId)}, ${q(row.author.profileId)}, ${q(row.sha)}, ${q(row.message)}, ${q(row.at)}, 48, 6, 2, 1)`).join(",\n") + ";");
push("");
push("INSERT INTO task_git_commit_link (id, task_id, git_commit_id, link_source, jira_key_snapshot, confidence) VALUES");
push(commitLinks.map((row) => `    (${q(row.id)}, ${q(row.taskId)}, ${q(row.commitId)}, 'COMMIT_MESSAGE', ${q(row.key)}, 'HIGH')`).join(",\n") + ";");
push("");
if (reviews.length) {
  push("INSERT INTO peer_review (id, sprint_id, reviewer_student_id, reviewee_student_id, star_rating, comment) VALUES");
  push(reviews.map((row) => `    (${q(row.id)}, ${q(row.sprintId)}, ${q(row.reviewer.profileId)}, ${q(row.reviewee.profileId)}, 4, ${q("Đã nhận xét phần việc của " + row.reviewee.fullName + ".")})`).join(",\n") + ";");
  push("");
}
push("INSERT INTO sync_job_log (id, target_system, target_id, job_type, status, started_at, completed_at, items_processed, items_failed) VALUES");
push(jobs.map((row) => `    (${q(row.id)}, ${q(row.system)}, ${q(row.projectId)}, 'INCREMENTAL', ${q(row.status)}, ${q(row.at)}, ${q(row.at)}, ${row.status === "FAILED" ? 0 : 20}, ${row.status === "FAILED" ? 1 : 0})`).join(",\n") + ";");
push("");
push("INSERT INTO project_group_weight_config (id, project_id, team_id, code_weight, test_weight, document_weight, research_weight, updated_by_user_id) VALUES");
push(weightRows.map((row) => `    (${q(row.id)}, ${q(row.projectId)}, ${q(row.teamId)}, 0.25000, 0.25000, 0.25000, 0.25000, ${lecturerUserExpr})`).join(",\n") + ";");
push("");
push("COMMIT;");
push("");
push("SELECT c.course_code, t.team_no, t.name AS team_name, p.name AS project_name,");
push("       (SELECT COUNT(*) FROM team_member tm WHERE tm.team_id = t.id) AS members,");
push("       (SELECT COUNT(*) FROM sprint s JOIN jira_integration j ON j.id = s.jira_integration_id WHERE j.project_id = p.id) AS sprints,");
push("       (SELECT COUNT(*) FROM task tk WHERE tk.project_id = p.id) AS tasks");
push("FROM course c");
push("JOIN team t ON t.course_id = c.id");
push("LEFT JOIN project p ON p.id = t.project_id");
push(`WHERE c.id IN (${q(courseA)}, ${q(courseB)})`);
push("ORDER BY c.course_code, t.team_no;");

if (projects.length !== teams.length) throw new Error(`expected ${teams.length} projects, got ${projects.length}`);
for (const project of projects) {
  const children = project.tasks.filter((item) => item.type === "SUBTASK");
  if (children.length < 2) throw new Error(`${project.projectKey} needs subtasks`);
  const used = new Map();
  for (const child of children) {
    if (child.label) throw new Error(`${child.key} subtask must not have a label`);
    if (child.points < 1 || child.points > 10) throw new Error(`${child.key} percent out of range`);
    used.set(child.parentTitle, (used.get(child.parentTitle) ?? 0) + child.points);
  }
  for (const [parent, points] of used) {
    if (points > 10) throw new Error(`${project.projectKey} ${parent} subtasks total ${points * 10}%`);
  }
}
for (const student of students) {
  if (!/^SE(17|18)0\d{3}$/.test(student.code)) throw new Error(student.code);
  if (student.email !== `${student.code.toLowerCase()}@fpt.edu.vn`) throw new Error(student.email);
}
for (const project of projects) {
  for (let index = 0; index < 4; index += 1) {
    const count = project.tasks.filter((item) => item.sprint === index).length;
    if (count < 5) {
      throw new Error(`${project.projectKey} sprint ${index + 1} has ${count} tasks`);
    }
  }
}

writeFileSync(outPath, lines.join("\n") + "\n", "utf8");
console.log(`wrote ${lines.length} lines, ${students.length} students, ${projects.length} projects`);
