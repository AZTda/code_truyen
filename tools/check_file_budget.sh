#!/usr/bin/env bash
# tools/check_file_budget.sh
# Script kiểm tra kích thước file trong dự án TruyenCloud theo Clean Architecture SRP (Max 350 lines).

MAX_LINES=350
WARNING_LINES=250

echo "🔍 Đang kiểm tra hạn mức dòng code trong dự án TruyenCloud (Giới hạn: $MAX_LINES dòng/tệp)..."
echo "-----------------------------------------------------------------------------------"

VIOLATIONS=0
WARNINGS=0

while IFS= read -r line; do
    count=$(echo "$line" | awk '{print $1}')
    file=$(echo "$line" | awk '{print $2}')
    
    if [ "$count" -gt "$MAX_LINES" ]; then
        echo "❌ [QUÁ TẢI] $count dòng: $file"
        VIOLATIONS=$((VIOLATIONS + 1))
    elif [ "$count" -gt "$WARNING_LINES" ]; then
        echo "⚠️  [CẢNH BÁO] $count dòng: $file"
        WARNINGS=$((WARNINGS + 1))
    fi
done < <(find app server -type f \( -name "*.kt" -o -name "*.py" \) \
  ! -path "*/.venv/*" \
  ! -path "*/build/*" \
  ! -path "*/.gradle/*" \
  ! -path "*/.idea/*" \
  ! -path "*/iosApp.xcodeproj/*" \
  -exec wc -l {} + | sort -rn | grep -v ' total$')

echo "-----------------------------------------------------------------------------------"
if [ "$VIOLATIONS" -gt 0 ]; then
    echo "🚨 Tìm thấy $VIOLATIONS tệp vượt quá $MAX_LINES dòng!"
    echo "👉 Cần chia nhỏ các tệp trên theo nguyên lý Single Responsibility Principle (SRP)."
else
    echo "✅ Tất cả các tệp đều đạt chuẩn kích thước dưới $MAX_LINES dòng!"
fi
echo "ℹ️  Số tệp trong khoảng cảnh báo (250 - $MAX_LINES dòng): $WARNINGS"
