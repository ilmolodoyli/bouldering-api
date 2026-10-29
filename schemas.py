from pydantic import BaseModel, model_validator
from typing import Optional
# схемы — это мой жесткий фейсконтроль. 
# они проверяют, чтобы фронт прислал мне правильный json, а не какую-то дичь.
# если данные кривые, сервак сам отобьет запрос еще до базы.
# схемы для участников 
# это то, что я жду от приложения при регистрации нового скалолаза.
class CompetitorCreate(BaseModel):
    full_name: str
    age: int
    category: str
# а это то, что мой сервак плюет обратно после сохранения в базу.
# он забирает поля сверху (имя, возраст, категорию) и приклеивает id из базы.
class CompetitorResponse(CompetitorCreate):
    id: int
    # эта штука нужна, чтобы пайдентик умел переводить объекты алхимии 
    # (из базы) в обычный json для фронта. без нее будет ошибка.
    class Config:
        from_attributes = True
#схемы для трасс
# какие данные нужны, чтобы я создал новую трассу
class RouteCreate(BaseModel):
    difficulty: str 
    age_category: str
# ответ моего сервака после создания трассы (возвращаю с айдишником)
class RouteResponse(RouteCreate):
    id: int
    class Config: 
        from_attributes = True
#схемы для результатов 
# самое важное — отправка попыток от судьи. тут я говорю: мне обязательно нужны айдишники чела и трассы. а попытки, если фронт их не пришлет, я сам заполню нулями по дефолту
class ResultCreate(BaseModel):
    competitor_id: int
    route_id: int
    total_attempt: int = 0
    zone_attempt: Optional[int] = None
    top_attempt: Optional[int] = None
    @model_validator(mode='after') # добавлена автоподстановка зоны при взятии топа
    def auto_fill_zone_if_top_reached(self):
        if self.top_attempt and self.top_attempt > 0:
            if not self.zone_attempt or self.zone_attempt == 0:
                self.zone_attempt = self.zone_attempt
        return self
# отдаю сохраненный результат обратно, чтобы фронт понял, что всё записалось
class ResultResponse(ResultCreate):
    id: int
    class Config:
        from_attributes = True