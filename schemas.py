from pydantic import BaseModel, model_validator
from typing import Optional
from models import UserRole, TournamentStage  # добавил импорт ролей и стадий турнира из моделей
# схемы — это мой жесткий фейсконтроль. 
# они проверяют, чтобы фронт прислал мне правильный json, а не какую-то дичь.
# если данные кривые, сервак сам отобьет запрос еще до базы.
# --- схемы для пользователей и ролей ---
# добавил: входной билет при первой регистрации в приложении
class UserRegister(BaseModel):
    username: str
    password: str
# добавил: смена бейджика — орг выдает новую роль человеку
class UserRoleUpdate(BaseModel):
    role: UserRole
# добавил: открытая визитка профиля без паролей для фронта
class UserResponse(BaseModel):
    id: int
    username: str
    role: UserRole
    class Config:
        from_attributes = True
# --- схемы для турниров ---
# добавил: создание самого турнира в календаре
class TournamentCreate(BaseModel):
    title: str
    stage: TournamentStage = TournamentStage.UPCOMING
# добавил: свисток судьи для переключения стадии (квалификация, финал)
class TournamentUpdateStage(BaseModel):
    stage: TournamentStage
# добавил: карточка турнира со статусом и айдишником для зрителей
class TournamentResponse(TournamentCreate):
    id: int
    class Config:
        from_attributes = True
# --- схемы для участников ---
# это то, что я жду от приложения при регистрации нового скалолаза.
class CompetitorCreate(BaseModel):
    full_name: str
    age: int
    category: str
    user_id: Optional[int] = None        # добавил: привязка к аккаунту пользователя
    tournament_id: Optional[int] = None  # добавил: привязка к конкретному турниру
# а это то, что мой сервак плюет обратно после сохранения в базу.
# он забирает поля сверху (имя, возраст, категорию) и приклеивает id из базы.
class CompetitorResponse(CompetitorCreate):
    id: int
    # эта штука нужна, чтобы пайдентик умел переводить объекты алхимии 
    # (из базы) в обычный json для фронта. без нее будет ошибка.
    class Config:
        from_attributes = True
# --- схемы для трасс ---
# какие данные нужны, чтобы я создал новую трассу
class RouteCreate(BaseModel):
    difficulty: str 
    age_category: str
    tournament_id: Optional[int] = None  # добавил: трасса теперь знает, к какому турниру относится
# ответ моего сервака после создания трассы (возвращаю с айдишником)
class RouteResponse(RouteCreate):
    id: int
    class Config: 
        from_attributes = True
# --- схемы для результатов ---
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
                self.zone_attempt = self.top_attempt  # заменил: исправил присваивание значения зоны от топа
        return self
# отдю сохраненный результат обратно, чтобы фронт понял, что всё записалось
class ResultResponse(ResultCreate):
    id: int
    class Config:
        from_attributes = True