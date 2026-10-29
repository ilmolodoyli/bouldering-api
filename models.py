import enum
from sqlalchemy import Column, Integer, String, ForeignKey, Enum
from database import Base
# роли как бейджики на фесте: спортсмен лазает, смотрящий судит трассу, орг рулит всем
class UserRole(str, enum.Enum):
    ATHLETE = "athlete"
    JUDGE = "judge"
    ORGANIZER = "organizer"
# стадии соревнований как таймлайн движухи
class TournamentStage(str, enum.Enum):
    UPCOMING = "upcoming"
    QUALIFICATION = "qualification"
    FINAL = "final"
    FINISHED = "finished"
# аккаунты людей в приложении, логин и пароль как ключ от раздевалки
class User(Base):
    __tablename__ = "users"
    id = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True, nullable=False)
    password_hash = Column(String, nullable=False) # хеш пароля, чтобы никто не подсмотрел
    role = Column(Enum(UserRole), default=UserRole.ATHLETE, nullable=False)
# сам фест или турнир, под крышей которого всё происходит
class Tournament(Base):
    __tablename__ = "tournaments"
    id = Column(Integer, primary_key=True, index=True)
    title = Column(String, nullable=False)
    stage = Column(Enum(TournamentStage), default=TournamentStage.UPCOMING, nullable=False)
# участники на скалодроме, привязаны к аккаунту и конкретному фестивалю
class Competitor(Base):
    __tablename__ = "competitors"
    id = Column(Integer, primary_key=True, index=True)
    full_name = Column(String, nullable=False)
    age = Column(Integer, nullable=False)
    category = Column(String, nullable=False)
    user_id = Column(Integer, ForeignKey("users.id"), nullable=True) # чей это профиль
    tournament_id = Column(Integer, ForeignKey("tournaments.id"), nullable=True) # на каком турнире выступает
# трассы, накрученные на стенке под этот турнир
class Route(Base):
    __tablename__ = "routes"
    id = Column(Integer, primary_key=True, index=True)
    difficulty = Column(String, nullable=False) # сложность типа 6b или 7a
    age_category = Column(String, nullable=False)
    tournament_id = Column(Integer, ForeignKey("tournaments.id"), nullable=True)
# судейский протокол: кто какую трассу пробовал и сколько сил оставил
class Result(Base):
    __tablename__ = "results"
    id = Column(Integer, primary_key=True, index=True)
    competitor_id = Column(Integer, ForeignKey("competitors.id"), nullable=False)
    route_id = Column(Integer, ForeignKey("routes.id"), nullable=False)
    total_attempt = Column(Integer, default=0)
    zone_attempt = Column(Integer, default=0)
    top_attempt = Column(Integer, default=0)